Health
======

Application health and readiness checks.

GET /api/v1/health
------------------

Returns the application status and index statistics.

**Request**

.. code-block:: http

   GET /api/v1/health HTTP/1.1
   Host: localhost:8080

**Response**

.. code-block:: json

   {
     "status": "UP",
     "index": {
       "totalEntities": 12847,
       "lastUpdated": "2026-03-18T02:00:00Z",
       "countBySource": {
         "OFAC_SDN": 12847
       },
       "countByType": {
         "INDIVIDUAL": 8234,
         "ENTITY": 4200,
         "VESSEL": 312,
         "AIRCRAFT": 101
       }
     }
   }

**Response Fields**

.. list-table::
   :header-rows: 1
   :widths: 30 12 58

   * - Field
     - Type
     - Description
   * - ``status``
     - string
     - Application status (``UP``)
   * - ``index.totalEntities``
     - integer
     - Total number of indexed sanctioned entities
   * - ``index.lastUpdated``
     - ISO 8601
     - When the index was last populated
   * - ``index.countBySource``
     - object
     - Entity count per list source
   * - ``index.countByType``
     - object
     - Entity count per entity type

**Example**

.. code-block:: bash

   curl http://localhost:8080/api/v1/health
