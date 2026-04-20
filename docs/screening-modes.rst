Screening Modes
===============

Three screening patterns for different throughput and latency requirements.

Single-Entity (Synchronous)
---------------------------

Screen one name and get results immediately.

.. code-block:: java

   ScreeningRequest req = ScreeningRequest.of("Vladimir Putin", 0.80);
   List<MatchResult> hits = engine.screen(req, entityIndex);

   for (MatchResult hit : hits) {
       System.out.printf("%s — %.2f (%s)%n",
           hit.entity().primaryName().fullName(),
           hit.score(),
           hit.matchAlgorithm());
   }

Batch Screening
---------------

Screen up to 1 000 names in a single HTTP request. The server processes each name
sequentially through the composite engine and returns individual results per name.

.. code-block:: java

   // Via REST API
   // POST /api/v1/screen/batch
   // {
   //   "requests": [
   //     {"name": "John Doe", "threshold": 0.80},
   //     {"name": "ACME Holdings", "entityType": "ENTITY"},
   //     {"name": "Ali Hassan", "threshold": 0.70}
   //   ]
   // }

   // Programmatic usage
   List<ScreeningRequest> batch = List.of(
       ScreeningRequest.of("John Doe", 0.80),
       ScreeningRequest.of("ACME Holdings", 0.80),
       ScreeningRequest.of("Ali Hassan", 0.70)
   );
   List<List<MatchResult>> results = batch.stream()
       .map(req -> engine.screen(req, entityIndex))
       .toList();

Address Screening
-----------------

Screen a free-text address parsed by libpostal into structured components,
then matched against entity addresses using weighted component scoring.

.. code-block:: java

   // Via REST API
   // POST /api/v1/screen/address
   // {"address": "123 Main Street, London, UK", "threshold": 0.60}

   // Programmatic usage
   AddressNormalizer normalizer = new AddressNormalizer();
   normalizer.init();
   AddressMatchService svc = new AddressMatchService(normalizer);

   List<AddressMatchResult> hits =
       svc.screen("Kremlin, Moscow, Russia", entityIndex, 0.50, 50);

Comparison
----------

.. list-table::
   :header-rows: 1
   :widths: 20 25 30 25

   * - Mode
     - Latency Profile
     - Use Case
     - Thread Model
   * - Single
     - Sub-millisecond per query
     - Real-time payment screening
     - Event-loop (Vert.x) or servlet thread (Spring)
   * - Batch
     - Linear in batch size
     - Nightly customer file screening
     - Single request, sequential processing
   * - Address
     - Depends on index size
     - OFAC address compliance checks
     - Same as single; libpostal parsing adds ~1ms

.. tip::

   For maximum batch throughput, use the Vert.x server — its non-blocking I/O
   model handles thousands of concurrent batch requests without thread pool exhaustion.
