Module Structure
================

Sieve is organized as a multi-module Maven project with strict dependency
rules. Each module has a single responsibility and well-defined API boundary.

Module Diagram
--------------

.. mermaid::

   graph TD
       SERVER["sieve-server<br/><small>Spring Boot REST API</small>"] --> MATCH
       SERVER --> INGEST
       CLI["sieve-cli<br/><small>Picocli CLI</small>"] --> MATCH
       CLI --> INGEST
       BENCH["sieve-benchmark<br/><small>JMH + stress tests</small>"] --> MATCH
       BENCH --> INGEST
       MATCH["sieve-match<br/><small>Matching engines</small>"] --> CORE
       INGEST["sieve-ingest<br/><small>List fetchers & parsers</small>"] --> CORE
       ADDR["sieve-address<br/><small>Address normalization</small>"] --> CORE
       SERVER --> ADDR

       style CORE fill:#e1f5fe,stroke:#0288d1
       style MATCH fill:#fff3e0,stroke:#f57c00
       style INGEST fill:#e8f5e9,stroke:#388e3c
       style SERVER fill:#fce4ec,stroke:#c62828
       style CLI fill:#f3e5f5,stroke:#7b1fa2
       style BENCH fill:#fff8e1,stroke:#f9a825
       style ADDR fill:#e0f2f1,stroke:#00897b

Directory Layout
----------------

.. code-block:: text

   sieve-aml/
   ├── sieve-core/          # Zero-dependency domain module
   ├── sieve-ingest/        # List fetchers and XML parsers
   ├── sieve-match/         # Matching engine implementations
   ├── sieve-address/       # Address normalization (libpostal)
   ├── sieve-server/        # Spring Boot REST API + JPA persistence
   ├── sieve-cli/           # Picocli command-line interface
   ├── sieve-benchmark/     # JMH microbenchmarks + HTTP stress tests
   ├── Dockerfile           # Multi-stage Docker build
   ├── docker-compose.yml   # Server + PostgreSQL
   └── pom.xml              # Parent POM

Module Details
--------------

sieve-core
^^^^^^^^^^

**Zero compile dependencies** (beyond ``slf4j-api``). Contains:

- **Domain model** — Java 21 records: ``SanctionedEntity``, ``NameInfo``,
  ``Address``, ``Identifier``, ``SanctionsProgram``
- **Enums** — ``EntityType``, ``ListSource``, ``NameType``, ``NameStrength``,
  ``ScriptType``, ``IdentifierType``
- **Index abstraction** — ``EntityIndex`` interface, ``InMemoryEntityIndex``
  implementation
- **Match SPI** — ``MatchEngine``, ``MatchResult``, ``ScreeningRequest``

All other modules depend on ``sieve-core``.

sieve-ingest
^^^^^^^^^^^^

Fetches and parses sanctions lists from official sources. Contains:

- **ListProvider SPI** — interface for sanctions list fetchers
- **IngestionOrchestrator** — coordinates multi-source ingestion with reporting
- **Provider implementations:**
  - ``OfacSdnProvider`` — OFAC SDN (StAX XML streaming parser, ETag delta detection)
  - ``EuConsolidatedProvider`` — EU Consolidated Financial Sanctions
  - ``UnConsolidatedProvider`` — UN Security Council Consolidated List
  - ``UkHmtProvider`` — UK HM Treasury

sieve-match
^^^^^^^^^^^^

Matching engine implementations:

- **ExactMatchEngine** — normalized exact string comparison
- **FuzzyMatchEngine** — Jaro-Winkler similarity with threshold
- **CompositeMatchEngine** — runs multiple engines, deduplicates, sorts
- **NormalizedNameCache** — pre-computed normalized names per entity
- **NgramIndex** — trigram inverted index for candidate selection
- **JaroWinkler** — pure Java string similarity algorithm
- **NameNormalizer** — string normalization with memoization

sieve-address
^^^^^^^^^^^^^

Address normalization using `libpostal <https://github.com/openvenues/libpostal>`_
(optional native dependency). Provides structured address parsing and
normalization for address-based matching.

sieve-server
^^^^^^^^^^^^^

Spring Boot 3.3 REST API:

- **Controllers** — screening, list management, health
- **DTOs** — request/response records with Bean Validation + OpenAPI schemas
- **JPA persistence** — optional PostgreSQL storage via Flyway migrations
- **Configuration** — ``@ConfigurationProperties`` with YAML binding
- **Error handling** — RFC 7807 Problem Details via ``@RestControllerAdvice``

sieve-cli
^^^^^^^^^^

Standalone Picocli CLI (no Spring dependency):

- **Commands** — ``fetch``, ``screen``, ``stats``, ``export``
- CI/CD-friendly exit codes (0 = clear, 1 = match, 2 = error)

sieve-benchmark
^^^^^^^^^^^^^^^^

Performance testing:

- **JMH microbenchmarks** — engine-level throughput measurement
- **HTTP stress test** — high-concurrency load test against the REST API
- Uses virtual threads for maximum concurrency

Dependency Rules
----------------

1. ``sieve-core`` has **zero compile dependencies** beyond SLF4J
2. Dependencies flow in one direction: ``server/cli → match → ingest → core``
3. No dependency cycles between modules
4. ``sieve-match`` does not depend on ``sieve-ingest`` (decoupled via ``EntityIndex``)
5. ``sieve-server`` is the only module with Spring dependencies
6. ``sieve-cli`` is the only module with Picocli dependencies
