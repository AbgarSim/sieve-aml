Architecture Overview
=====================

Modular, embeddable sanctions screening with zero mandatory framework dependencies.

Component Diagram
-----------------

.. graphviz::

   digraph architecture {
       rankdir=LR;
       node [shape=box, style="rounded,filled", fontname="Helvetica", fontsize=10];
       edge [fontsize=9];

       core      [label="sieve-core\n(entity model, index SPI,\naudit events)", fillcolor="#e8f5e9"];
       ingest    [label="sieve-ingest\n(OFAC XML, EU XML,\nUN XML, UK CSV)", fillcolor="#e3f2fd"];
       match     [label="sieve-match\n(exact, fuzzy, phonetic,\ntoken engines)", fillcolor="#fff3e0"];
       address   [label="sieve-address\n(LibPostal JNI,\naddress normalization)", fillcolor="#fce4ec"];
       server    [label="sieve-server\n(Vert.x REST API)", fillcolor="#f3e5f5"];
       spring    [label="sieve-spring-server\n(Spring Boot, JPA,\nSwagger, scheduling)", fillcolor="#f3e5f5"];
       cli       [label="sieve-cli\n(Picocli)", fillcolor="#efebe9"];
       bench     [label="sieve-benchmark\n(JMH, JMeter)", fillcolor="#eceff1"];

       ingest  -> core;
       match   -> core;
       address -> core;
       server  -> match;
       server  -> ingest;
       server  -> address;
       spring  -> match;
       spring  -> ingest;
       spring  -> address;
       cli     -> match;
       cli     -> ingest;
       bench   -> match;
       bench   -> ingest;
   }

Request Lifecycle
-----------------

1. **HTTP request** arrives at ``POST /api/v1/screen`` with a JSON body containing ``name``, optional ``threshold``, ``entityType``, and ``sources``.
2. **Request parsing** — the handler extracts parameters and constructs a ``ScreeningRequest`` domain object.
3. **Entity normalization** — the ``NormalizedNameCache`` lowercases, strips diacritics (Unicode NFKD), and collapses whitespace for both the query and all indexed entity names.
4. **N-gram candidate filtering** — the ``NgramIndex`` selects candidate entities sharing trigrams with the query, avoiding a full linear scan for fuzzy/phonetic engines.
5. **Matching pipeline** — the ``CompositeMatchEngine`` dispatches the query to all registered engines (exact, fuzzy, phonetic, token) in parallel, collects results, deduplicates by entity ID, and keeps the highest score per entity.
6. **Scoring & ranking** — results are sorted by descending score and truncated to ``maxResults``.
7. **Audit emission** — a ``ScreeningAuditEvent`` is emitted (including no-match outcomes) via the pluggable ``ScreeningAuditEmitter``.
8. **Response** — JSON response with ``query``, ``totalMatches``, ``screenedAt``, and the ranked ``results`` array.

Pluggability Model
------------------

Extension points use the **strategy pattern** — inject your own implementation at construction time.

.. list-table::
   :header-rows: 1
   :widths: 25 35 40

   * - SPI / Interface
     - Package
     - Purpose
   * - ``MatchEngine``
     - ``dev.sieve.core.match``
     - Custom matching algorithm; register in ``CompositeMatchEngine``
   * - ``ListProvider``
     - ``dev.sieve.ingest``
     - Custom sanctions list fetcher/parser
   * - ``EntityIndex``
     - ``dev.sieve.core.index``
     - Alternative index backend (e.g., Lucene, Redis)
   * - ``ScreeningAuditEmitter``
     - ``dev.sieve.core.audit``
     - Custom audit sink (Kafka, JDBC, S3)
   * - ``AddressNormalizer``
     - ``dev.sieve.address``
     - Alternative address parsing/normalization strategy

.. tip::

   All SPIs accept implementations via constructor injection — no classpath scanning,
   no reflection, no service loader XML.
