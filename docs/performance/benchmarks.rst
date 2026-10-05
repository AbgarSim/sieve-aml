Benchmarks
==========

This page records measured runs. The first section is a run against every list
Sieve fetches, with a labelled matching evaluation; the later sections are the
earlier OFAC SDN-only results, kept for comparison.

.. contents:: On this page
   :local:
   :depth: 2

All lists, 5 October 2026
-------------------------

Run by the ``Benchmark`` workflow (``.github/workflows/benchmark.yml``) on a
GitHub-hosted runner, `run 37382995484
<https://github.com/AbgarSim/sieve-aml/actions/runs/37382995484>`_, with
``sieve-benchmark real`` (seed 20261005).

- **Machine:** GitHub-hosted ``ubuntu-latest`` runner, 4 vCPUs (2 cores of an Intel Xeon 6973P-C,
  2 threads each), 15 GiB RAM; Java 21.0.12, 10 GB maximum heap
- **Data:** every list fetched live from its publisher at run time: 214,616 records
  from 34 lists in 261 s (Israel's list failed to download that day; Ukraine's
  needs an API key and was left out), 210,833 distinct records and 458,635 names
  including aliases once indexed
- **Engine:** the same four-engine composite the servers use (exact, fuzzy,
  phonetic, token), called in process

Index
^^^^^

.. list-table::
   :header-rows: 1
   :widths: 60 20

   * - Measure
     - Value
   * - Name cache and trigram index built in
     - 4.9 s
   * - Heap in use for records, name cache and index (after GC)
     - 790 MB

Matching accuracy
^^^^^^^^^^^^^^^^^

Three generated query sets, 2,000 queries each, screened by name only (no date
of birth, country or entity type), as the API's default request:

- **Same entity on another list:** a record's name screened to find a
  *different* list's record of the same person, company or vessel. Pairs are
  linked by a shared passport, national id, IMO number, LEI, SWIFT/BIC, tax or
  registration number, so the label does not depend on the names. 978 of the
  2,000 pairs spell the name differently on the two lists.
- **Spelling variants:** a listed multi-word name with one typo, swapped or
  dropped letter, transliteration change, reversed word order or dropped middle
  word.
- **Unlisted customers:** ordinary first-and-last names and company names
  assembled from common words, which are not on any list. Any alert on these is
  a false positive.

Share of same-entity and spelling queries whose expected record is returned at
or above the threshold (recall):

.. list-table::
   :header-rows: 1
   :widths: 40 10 10 10 10 10

   * - Query set
     - 0.70
     - 0.80
     - 0.85
     - 0.90
     - 0.95
   * - Same entity on another list (2,000)
     - 95.6%
     - 93.4%
     - 93.1%
     - 92.0%
     - 90.6%
   * - … of which spelled differently (978)
     - 91.0%
     - 86.5%
     - 85.8%
     - 83.5%
     - 80.8%
   * - Spelling variants (2,000)
     - 98.7%
     - 96.5%
     - 96.5%
     - 96.2%
     - 94.9%

Spelling variants by change, found at 0.80: dropped letter 98.0%, swapped
letters 98.9%, transliteration 99.2%, typo 97.8%, reversed word order 91.9%,
dropped middle word 91.0%.

Unlisted customers (2,000 names, 409 of them companies):

.. list-table::
   :header-rows: 1
   :widths: 40 10 10 10 10 10

   * - Measure
     - 0.70
     - 0.80
     - 0.85
     - 0.90
     - 0.95
   * - Names raising at least one alert
     - 100%
     - 99.4%
     - 95.9%
     - 73.0%
     - 44.1%
   * - Hits per alerted name
     - 384
     - 201
     - 69
     - 17.9
     - 14.5

**The false-positive rate of name-only screening is high, and this is the main
finding of the run.** Common first-and-last names screened against 210,833
records (88,000 of them politically exposed persons and their relatives) almost
always meet a listed name that is similar. Of the 2,000 unlisted names:

- 112 (5.6%) are identical to a listed name: real namesakes that only a date of
  birth, nationality or identifier can tell apart.
- 610 (30.5%) have their best hit at exactly 0.95, the fixed score of the
  phonetic engine, whose Double Metaphone codes merge many surnames (for example
  Hussain, Hassan and Hosni all encode as HSN). Only 67 of the 4,000
  same-entity and spelling queries find their expected record at exactly that
  score.
- Wikidata's politically exposed persons supply 58% of the best false hits at
  0.90; US Consolidated Screening List records supply the most for company
  names.

Screening on more than the name (date of birth, country, identifiers) and
calibrating the per-engine scores are the next matching milestones on the
roadmap. Re-run this evaluation after each of them.

Limits of the evaluation: the unlisted names are generated, not drawn from a
real customer file; the same-entity pairs are those the lists themselves tie by
an identifier, which favours sanctioned companies and vessels; and every query
is name-only.

Latency and throughput (in process)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

One screening at threshold 0.80 over all 210,833 records, single thread, 6,000
queries drawn from the sets above: mean 60.6 ms, p50 51.3 ms, p95 136.4 ms,
p99 214.9 ms, maximum 370.8 ms.

.. list-table::
   :header-rows: 1
   :widths: 20 25 25 25

   * - Threads
     - Screenings/s
     - p50
     - p99
   * - 1
     - 15
     - 52 ms
     - 247 ms
   * - 2
     - 28
     - 57 ms
     - 227 ms
   * - 4
     - 38
     - 86 ms
     - 334 ms
   * - 8
     - 38
     - 166 ms
     - 688 ms
   * - 16
     - 39
     - 339 ms
     - 1,232 ms
   * - 64
     - 38
     - 1,332 ms
     - 4,979 ms

Throughput levels off at the runner's 4 vCPUs, about 38 screenings a second or 3.3 million
a day. It is far lower than the OFAC SDN-only figures below because the index
is ten times larger and holds many common names, so far more candidates reach
the fuzzy and token engines per query.

Over HTTP (Vert.x server)
^^^^^^^^^^^^^^^^^^^^^^^^^

The Vert.x server loaded with every list on the same kind of runner (`run
37386340586 <https://github.com/AbgarSim/sieve-aml/actions/runs/37386340586>`_):
loading took 293 s and the process held 3.3 GB resident afterwards (10 GB
maximum heap). ``sieve-benchmark http`` then posted the 6,000 query names at
threshold 0.80 for 20 s per level after a 5 s warm-up, with client and server
sharing the 4 vCPUs.

.. list-table::
   :header-rows: 1
   :widths: 15 20 20 20 20 10

   * - Clients
     - Requests/s
     - p50
     - p95
     - p99
     - Errors
   * - 1
     - 18
     - 46 ms
     - 122 ms
     - 182 ms
     - 0
   * - 4
     - 45
     - 74 ms
     - 199 ms
     - 281 ms
     - 0
   * - 16
     - 44
     - 295 ms
     - 785 ms
     - 1,227 ms
     - 0
   * - 64
     - 44
     - 1,376 ms
     - 1,973 ms
     - 2,562 ms
     - 0
   * - 256
     - 46
     - 5,557 ms
     - 6,366 ms
     - 6,777 ms
     - 0

A first run the same evening (`run 37384943162
<https://github.com/AbgarSim/sieve-aml/actions/runs/37384943162>`_) found the
server screening one request at a time: 11 requests a second at every client
count and 27 timeouts at 256 clients, because every connection was served by
one event loop that also ran the match. Screening now runs on Vert.x's worker
threads, and the table above is the run after that change.

Reproduce
^^^^^^^^^

Run the ``Benchmark`` workflow from the Actions tab, or locally:

.. code-block:: bash

   mvn -B package -pl sieve-benchmark -am -DskipTests
   java -Xmx10g -jar sieve-benchmark/target/sieve-benchmark-0.1.0-SNAPSHOT.jar \
     real --exclude UA_NSDC --out benchmark-report
   # with a server running and loaded:
   java -jar sieve-benchmark/target/sieve-benchmark-0.1.0-SNAPSHOT.jar \
     http --url http://localhost:8080/api/v1/screen --names benchmark-report/names.txt

``benchmark-report/queries.tsv`` lists every evaluation query with the expected
record, its score and the best hit.

OFAC SDN only, earlier results
------------------------------

The sections below are the original measurements against the OFAC SDN list
alone, on a laptop, through the Spring Boot server, and were already in the
repository on 28 April 2026. They predate the other lists and the current name
normalisation, so they are not comparable with the run above.

Test Environment
^^^^^^^^^^^^^^^^

- **Hardware:** Apple Silicon (M-series), 16GB RAM
- **JVM:** Java 21 (Eclipse Temurin)
- **Dataset:** OFAC SDN (~20,000 sanctioned entities, ~100,000 names including aliases)
- **Tool:** Custom HTTP stress test using virtual threads (``sieve-benchmark``)

Optimization Evolution
^^^^^^^^^^^^^^^^^^^^^^

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
^^^^^^^^^^^^^^^^

v1 — Baseline
"""""""""""""

Full linear scan with per-query normalization. Every query normalizes every
entity name and runs Jaro-Winkler against all ~100,000 names.

.. code-block:: text

   ═══ Phase 2: Sustained Load ═══
   Concurrency  Requests  Throughput  Avg (µs)     P50 (µs)     P99 (µs)     Errors
   200          100       4           18,007,406   17,784,685   22,290,343   0

   Peak throughput:  4 req/sec
   Avg latency:      18,007,406 µs (18 seconds)

v2 — N-gram Index + Name Cache
""""""""""""""""""""""""""""""

Pre-normalized names at index load time. Trigram inverted index reduces
candidate set from 20,000 to ~50–200 entities per query.

.. code-block:: text

   ═══ Phase 2: Sustained Load ═══
   Concurrency  Requests  Throughput  Avg (µs)  P50 (µs)  P99 (µs)  Errors
   200          100       435         131,320   125,504   225,753   0

   Peak throughput:  435 req/sec
   Avg latency:      131,320 µs

v3 — Full Optimization Suite
""""""""""""""""""""""""""""

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
^^^^^^^^^^^^^^^^^^^^^^

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
^^^^^^^^^^^^^^^^^^^^^^^^

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
^^^^^^^^^^^^^^^^^^

These runs used an HTTP stress tool that is no longer in the module; the
``http`` command above replaces it. Run JMH microbenchmarks (engine-level, no HTTP):

.. code-block:: bash

   java -jar sieve-benchmark/target/sieve-benchmark-0.1.0-SNAPSHOT.jar jmh
