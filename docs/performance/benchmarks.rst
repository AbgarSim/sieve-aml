Benchmarks
==========

This page documents Sieve's performance characteristics across three
optimization phases, measured against a real OFAC SDN dataset (~20,000 entities).

.. contents:: On this page
   :local:
   :depth: 2

Test Environment
----------------

- **Hardware:** Apple Silicon (M-series), 16GB RAM
- **JVM:** Java 21 (Eclipse Temurin)
- **Dataset:** OFAC SDN (~20,000 sanctioned entities, ~100,000 names including aliases)
- **Tool:** Custom HTTP stress test using virtual threads (``sieve-benchmark``)

Optimization Evolution
----------------------

.. list-table::
   :header-rows: 1
   :widths: 30 20 20 15

   * - Version
     - Single-Thread Latency
     - Peak Throughput
     - Improvement
   * - v1 — Baseline (linear scan)
     - 590ms
     - 4 req/s
     - —
   * - v2 — N-gram + name cache
     - 12ms
     - 435 req/s
     - 100×
   * - v3 — All optimizations
     - 7.5ms
     - 931 req/s
     - 230×

Detailed Results
----------------

v1 — Baseline
^^^^^^^^^^^^^^

Full linear scan with per-query normalization. Every query normalizes every
entity name and runs Jaro-Winkler against all ~100,000 names.

.. code-block:: text

   ═══ Phase 2: Sustained Load ═══
   Concurrency  Requests  Throughput  Avg (µs)     P50 (µs)     P99 (µs)     Errors
   200          100       4           18,007,406   17,784,685   22,290,343   0

   Peak throughput:  4 req/sec
   Avg latency:      18,007,406 µs (18 seconds)

v2 — N-gram Index + Name Cache
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Pre-normalized names at index load time. Trigram inverted index reduces
candidate set from 20,000 to ~50–200 entities per query.

.. code-block:: text

   ═══ Phase 2: Sustained Load ═══
   Concurrency  Requests  Throughput  Avg (µs)  P50 (µs)  P99 (µs)  Errors
   200          100       435         131,320   125,504   225,753   0

   Peak throughput:  435 req/sec
   Avg latency:      131,320 µs

v3 — Full Optimization Suite
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

All optimizations applied: threshold-aware early exit, ThreadLocal array
reuse, length-ratio pre-filter, memoized normalization, reduced logging.

.. code-block:: text

   ═══ Phase 1: Ramp-Up ═══
   Concurrency  Requests  Throughput  Avg (µs)  P50 (µs)   P99 (µs)   Errors
   1            250       131         7,549     7,207      12,358     0
   10           250       755         12,624    11,486     34,036     0
   50           250       903         46,084    39,016     151,771    0
   100          250       943         83,796    80,736     228,419    0

   ═══ Phase 2: Sustained Load ═══
   Concurrency  Requests  Throughput  Avg (µs)  P50 (µs)   P99 (µs)   Errors
   200          1000      931         175,530   119,844    621,418    0

   ═══ Phase 3: Threshold Sensitivity ═══
   Threshold  Throughput  Avg (µs)  P99 (µs)
   0.70       1,042       76,814    292,844
   0.80       894         84,522    364,805
   0.85       899         92,824    379,863
   0.90       1,109       71,145    266,972

   Peak throughput:  931 req/sec
   Avg latency:      175,530 µs (includes HTTP overhead)

Optimization Breakdown
----------------------

.. list-table::
   :header-rows: 1
   :widths: 40 30 30

   * - Optimization
     - Technique
     - Impact
   * - Pre-normalized name cache
     - ``ConcurrentHashMap`` per entity
     - Eliminates ~100k regex ops/query
   * - N-gram inverted index
     - Trigram → entity ID lookup
     - 20,000 → ~50 candidates
   * - Threshold-aware early exit
     - Length-ratio upper bound check
     - Skips impossible comparisons
   * - ThreadLocal array reuse
     - Reusable ``boolean[]`` in JaroWinkler
     - Zero GC pressure in hot path
   * - Length-ratio pre-filter
     - Skip if name lengths differ >3×
     - Further reduces candidate set
   * - Memoized normalization
     - Cached ``NameNormalizer`` results
     - No redundant regex for repeated queries
   * - Hot-path logging reduction
     - ``log.info`` → ``log.debug``
     - Eliminates string formatting overhead
   * - CompositeEngine fast path
     - Skip dedup for single engine
     - Reduces HashMap allocation

Where Time Is Spent (v3)
------------------------

At 7.5ms single-thread latency, the matching engine itself accounts for
<1ms. The remaining time is Spring Boot HTTP overhead:

.. list-table::
   :header-rows: 1
   :widths: 40 30

   * - Component
     - Estimated Time
   * - JSON deserialization (Jackson)
     - ~1–2ms
   * - Bean validation (``@Valid``)
     - ~0.5ms
   * - **Matching engine**
     - **~0.4ms**
   * - JSON serialization (response)
     - ~2–4ms
   * - HTTP/TCP + servlet dispatch
     - ~1–2ms

Running Benchmarks
------------------

Build and run the stress test:

.. code-block:: bash

   # Build the project
   mvn clean package -DskipTests

   # Start the server (in another terminal)
   java -jar sieve-server/target/sieve-server-0.1.0-SNAPSHOT.jar

   # Run the stress test
   java -jar sieve-benchmark/target/sieve-benchmark-0.1.0-SNAPSHOT.jar stress \
     --url=http://localhost:8080/api/v1/screen \
     --requests=1000 \
     --concurrency=200

Run JMH microbenchmarks (engine-level, no HTTP):

.. code-block:: bash

   java -jar sieve-benchmark/target/sieve-benchmark-0.1.0-SNAPSHOT.jar jmh
