#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# Sieve AML — Vert.x server launcher (low-latency tuned)
#
# Uses Generational ZGC for sub-millisecond GC pauses.
# Works on JDK 21+ (GraalVM CE/EE, Corretto, Temurin, etc.)
#
# Usage:
#   ./run-server.sh                              # defaults (system java)
#   ./run-server.sh --port 9090                  # custom port
#   ./run-server.sh --threshold 0.85             # custom threshold
#   JAVA_OPTS="-Xmx4g" ./run-server.sh          # override heap
#   GRAALVM_HOME=~/Library/Java/JavaVirtualMachines/graalvm-jdk-23.0.2+7.1/Contents/Home ./run-server.sh
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${SCRIPT_DIR}/target/sieve-server-0.1.0-SNAPSHOT.jar"

if [[ ! -f "${JAR}" ]]; then
    echo "ERROR: Server jar not found: ${JAR}"
    echo "  Build first: mvn -B clean package -pl sieve-server -am -DskipTests"
    exit 1
fi

# ── Locate Java binary (prefer GRAALVM_HOME > JAVA_HOME > PATH) ─
if [[ -n "${GRAALVM_HOME:-}" ]]; then
    JAVA="${GRAALVM_HOME}/bin/java"
elif [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="${JAVA_HOME}/bin/java"
else
    JAVA="java"
fi

if ! "${JAVA}" -version &>/dev/null; then
    echo "ERROR: Java not found at: ${JAVA}"
    exit 1
fi

# ── GC: Generational ZGC (sub-ms pauses, JDK 21+) ──────────────
# On JDK 23+ ZGenerational is the default; on 21-22 we enable it explicitly.
JAVA_MAJOR=$("${JAVA}" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
GC_OPTS=( -XX:+UseZGC )
if [[ "${JAVA_MAJOR}" -lt 23 ]]; then
    GC_OPTS+=( -XX:+ZGenerational )
fi

# ── Heap: size to workload (sanctions index ~200-500 MB) ────────
HEAP_OPTS=(
    -Xms512m
    -Xmx2g
    -XX:+AlwaysPreTouch
)

# ── JIT: aggressive optimization after warm-up ──────────────────
JIT_OPTS=(
    -XX:+TieredCompilation
    -XX:ReservedCodeCacheSize=256m
    -XX:+UseCompressedOops
)

# ── Vert.x / Netty tuning ──────────────────────────────────────
VERTX_OPTS=(
    -Dio.netty.tryReflectionSetAccessible=true
    -Dio.netty.leakDetection.level=disabled
    --add-opens java.base/java.nio=ALL-UNNAMED
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED
)

# ── Allow user overrides via JAVA_OPTS env var ──────────────────
USER_OPTS=(${JAVA_OPTS:-})

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Sieve AML Server"
echo "  JVM:  $("${JAVA}" -version 2>&1 | head -1)"
echo "  GC:   Generational ZGC"
echo "  Heap: ${HEAP_OPTS[0]} / ${HEAP_OPTS[1]}"
echo "  JAR:  ${JAR}"
echo "═══════════════════════════════════════════════════════════════"
echo ""

exec "${JAVA}" \
    "${GC_OPTS[@]}" \
    "${HEAP_OPTS[@]}" \
    "${JIT_OPTS[@]}" \
    "${VERTX_OPTS[@]}" \
    "${USER_OPTS[@]}" \
    -jar "${JAR}" \
    "$@"
