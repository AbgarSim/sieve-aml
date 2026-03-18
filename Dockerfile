# =============================================================================
# Sieve AML — Multi-stage Docker build
#
# Prerequisites:
#   - Docker >= 20.10 (BuildKit enabled by default)
#   - ~4 GB disk for libpostal data model download during build
#   - Internet access (GitHub, Maven Central, S3 for Senzing model)
#
# Build:
#   docker compose build            # via compose
#   docker build -t sieve-aml .     # standalone
#
# Run:
#   docker compose up                                       # with PostgreSQL
#   docker run -p 8080:8080 \                               # standalone
#     -e SIEVE_ADDRESS_LIBPOSTAL_ENABLED=true \
#     -e SIEVE_ADDRESS_LIBPOSTAL_DATA_DIR=/opt/libpostal-data \
#     sieve-aml
#
# Stages:
#   1. libpostal-build  — compile libpostal C library + download Senzing model
#   2. java-build       — compile Java app + build JNI native library
#   3. (runtime)        — lean JRE image with app, native libs, and model data
# =============================================================================

# =============================================================================
# Stage 1: Build libpostal from source + download Senzing v1.2.0 model
#
# Prerequisites installed in this stage:
#   - autoconf, automake, libtool  (autotools for ./configure && make)
#   - pkg-config                   (dependency resolution for libpostal)
#   - curl                         (downloading Senzing model from S3)
#   - gcc, g++, make               (C/C++ compilation)
#   - git                          (cloning libpostal source)
#   - ca-certificates              (HTTPS for git clone and curl)
#
# Outputs (consumed by later stages):
#   /usr/local/lib/libpostal*           — shared libraries
#   /usr/local/include/libpostal/       — C headers (for JNI compilation)
#   /opt/libpostal-data/                — model data (Senzing v1.2.0 parser)
# =============================================================================
FROM ubuntu:22.04 AS libpostal-build

RUN apt-get update && apt-get install -y --no-install-recommends \
        autoconf automake libtool pkg-config curl gcc g++ make git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 https://github.com/openvenues/libpostal /opt/libpostal-src \
    && cd /opt/libpostal-src \
    && ./bootstrap.sh \
    && ./configure --datadir=/opt/libpostal-data --prefix=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && ldconfig

# Autoconf --datadir installs to ${datadir}/libpostal/ — flatten into /opt/libpostal-data/
# so that transliteration, language_classifier, numex, address_expansions, and parser
# are all directly under the data dir that we pass to libpostal_setup_datadir().
RUN if [ -d /opt/libpostal-data/libpostal ]; then \
        cp -rn /opt/libpostal-data/libpostal/* /opt/libpostal-data/ ; \
    fi

# Replace the default parser model with the Senzing v1.2.0 improved model
# (~4% avg accuracy improvement across 89 countries, improved CJK parsing)
# Source: https://github.com/Senzing/libpostal-data
RUN curl -sL https://public-read-libpostal-data.s3.amazonaws.com/v1.2.0/parser.tar.gz \
    | tar -xz -C /opt/libpostal-data

# =============================================================================
# Stage 2: Build Java application + JNI native library
#
# Prerequisites installed / copied in this stage:
#   - maven:3.9-eclipse-temurin-21  (base image — JDK 21 + Maven 3.9)
#   - gcc, make                     (JNI native library compilation)
#   - libpostal headers + libs      (copied from stage 1 for JNI linking)
#
# Outputs (consumed by runtime stage):
#   /app/sieve-server/target/*.jar                          — Spring Boot fat JAR
#   /app/sieve-address/src/main/native/libsieve_postal.so   — JNI shared library
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS java-build

# Install gcc for JNI compilation; copy libpostal headers + libs from stage 1
RUN apt-get update && apt-get install -y --no-install-recommends gcc make libc-dev \
    && rm -rf /var/lib/apt/lists/*
COPY --from=libpostal-build /usr/local/include/libpostal /usr/local/include/libpostal
COPY --from=libpostal-build /usr/local/lib/libpostal* /usr/local/lib/
RUN ldconfig

WORKDIR /app

# Copy POM files first for dependency caching
COPY pom.xml .
COPY sieve-core/pom.xml sieve-core/
COPY sieve-address/pom.xml sieve-address/
COPY sieve-ingest/pom.xml sieve-ingest/
COPY sieve-match/pom.xml sieve-match/
COPY sieve-server/pom.xml sieve-server/
COPY sieve-cli/pom.xml sieve-cli/
COPY sieve-benchmark/pom.xml sieve-benchmark/
RUN mvn dependency:go-offline -B

# Copy source and build Java (exclude benchmark module — not needed at runtime)
COPY . .
RUN mvn -B -DskipTests clean package -pl '!sieve-benchmark'

# Build JNI native library (links against libpostal from stage 1)
RUN cd sieve-address/src/main/native && make

# =============================================================================
# Stage 3: Lean runtime image
#
# Contents:
#   - Eclipse Temurin JRE 21 (base)
#   - libpostal shared library   (address parsing at runtime)
#   - libpostal data directory   (Senzing v1.2.0 model — ~2 GB)
#   - libsieve_postal.so         (JNI bridge between Java and libpostal)
#   - app.jar                    (Spring Boot application)
#
# Required environment variables (set via docker-compose.yml or -e flags):
#   SIEVE_ADDRESS_LIBPOSTAL_ENABLED   — "true" to activate address normalization
#   SIEVE_ADDRESS_LIBPOSTAL_DATA_DIR  — path to model data (default: /opt/libpostal-data)
#   SPRING_PROFILES_ACTIVE            — "postgres" to use PostgreSQL backend
#   POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
# =============================================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy libpostal shared library
COPY --from=libpostal-build /usr/local/lib/libpostal* /usr/local/lib/

# Copy libpostal data (Senzing model)
COPY --from=libpostal-build /opt/libpostal-data /opt/libpostal-data

# Copy JNI native library
COPY --from=java-build /app/sieve-address/src/main/native/libsieve_postal.so /usr/local/lib/

RUN ldconfig

# Copy application JAR
COPY --from=java-build /app/sieve-server/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.library.path=/usr/local/lib", "-jar", "app.jar"]
