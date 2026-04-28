Spring Boot Integration
=======================

Optional auto-configuration for the ``sieve-spring-server`` module.

Auto-Configured Beans
---------------------

.. list-table::
   :header-rows: 1
   :widths: 30 30 40

   * - Bean
     - Type
     - Description
   * - ``entityIndex``
     - ``InMemoryEntityIndex``
     - Thread-safe in-memory entity store
   * - ``matchEngine``
     - ``CompositeMatchEngine``
     - All four engines (exact, fuzzy, phonetic, token)
   * - ``addressMatchService``
     - ``AddressMatchService``
     - Address screening with libpostal normalizer
   * - ``addressNormalizer``
     - ``AddressNormalizer``
     - LibPostal JNI bridge (fallback if native lib absent)
   * - ``ingestionOrchestrator``
     - ``IngestionOrchestrator``
     - Multi-source list fetcher
   * - ``screeningMapper``
     - ``ScreeningMapper``
     - Domain ↔ DTO conversion

REST Endpoints
--------------

.. list-table::
   :header-rows: 1
   :widths: 10 35 55

   * - Method
     - Endpoint
     - Description
   * - ``POST``
     - ``/api/v1/screen``
     - Screen a single name
   * - ``POST``
     - ``/api/v1/screen/batch``
     - Batch screen up to 1 000 names
   * - ``POST``
     - ``/api/v1/screen/address``
     - Screen a free-text address
   * - ``GET``
     - ``/api/v1/health``
     - Health check + index stats
   * - ``GET``
     - ``/api/v1/lists``
     - Sanctions list status
   * - ``GET``
     - ``/api/v1/lists/{source}/entities``
     - Paginated entity browser
   * - ``POST``
     - ``/api/v1/lists/refresh``
     - Trigger list re-ingestion

Request / Response Examples
---------------------------

**Screen request:**

.. code-block:: json

   {"name": "Vladimir Putin", "threshold": 0.80, "entityType": "INDIVIDUAL"}

**Screen response:**

.. code-block:: json

   {
     "query": "Vladimir Putin",
     "totalMatches": 1,
     "screenedAt": "2026-03-19T15:30:00Z",
     "results": [{
       "entity": {
         "id": "36360",
         "entityType": "INDIVIDUAL",
         "listSource": "OFAC_SDN",
         "primaryName": "PUTIN, Vladimir Vladimirovich",
         "aliases": [],
         "addresses": [{"city": "Moscow", "country": "RU"}],
         "programs": ["UKRAINE-EO13661"]
       },
       "score": 0.92,
       "matchedField": "primaryName",
       "matchAlgorithm": "FUZZY"
     }]
   }

Health Indicator
----------------

The ``/api/v1/health`` endpoint returns index statistics useful for monitoring
list freshness:

.. code-block:: json

   {
     "status": "UP",
     "index": {
       "totalEntities": 12847,
       "countBySource": {"OFAC_SDN": 12847},
       "lastUpdated": "2026-03-19T14:00:00Z"
     }
   }

.. tip::

   Wire the ``lastUpdated`` field to your monitoring system and alert if the index
   is older than your list update SLA (e.g., 24 hours for OFAC SDN).
