Lists
=====

Manage sanctions list sources — view status, browse entities, and trigger
re-ingestion.

.. contents:: On this page
   :local:
   :depth: 2

GET /api/v1/lists
-----------------

Returns the status of all known sanctions list sources.

**Request**

.. code-block:: http

   GET /api/v1/lists HTTP/1.1
   Host: localhost:8080

**Response**

.. code-block:: json

   {
     "lists": [
       {
         "source": "OFAC_SDN",
         "entityCount": 12847,
         "lastFetched": "2026-03-18T02:00:00Z",
         "status": "LOADED"
       },
       {
         "source": "EU_CONSOLIDATED",
         "entityCount": 0,
         "lastFetched": null,
         "status": "EMPTY"
       }
     ]
   }

**Response Fields**

.. list-table::
   :header-rows: 1
   :widths: 25 12 63

   * - Field
     - Type
     - Description
   * - ``lists[].source``
     - string
     - List source identifier
   * - ``lists[].entityCount``
     - integer
     - Number of entities loaded from this source
   * - ``lists[].lastFetched``
     - ISO 8601
     - When the list was last successfully fetched (``null`` if never)
   * - ``lists[].status``
     - string
     - ``LOADED`` or ``EMPTY``

GET /api/v1/lists/{source}/entities
-----------------------------------

Returns a paginated list of entities from a specific sanctions list.

**Request**

.. code-block:: http

   GET /api/v1/lists/OFAC_SDN/entities?page=0&size=20 HTTP/1.1
   Host: localhost:8080

**Path Parameters**

.. list-table::
   :header-rows: 1
   :widths: 20 80

   * - Parameter
     - Description
   * - ``source``
     - List source identifier (e.g., ``OFAC_SDN``, ``EU_CONSOLIDATED``)

**Query Parameters**

.. list-table::
   :header-rows: 1
   :widths: 20 15 15 50

   * - Parameter
     - Type
     - Default
     - Description
   * - ``page``
     - integer
     - ``0``
     - Zero-based page number
   * - ``size``
     - integer
     - ``20``
     - Number of entities per page

POST /api/v1/lists/refresh
--------------------------

Triggers an immediate re-ingestion of all enabled sanctions list sources.

**Request**

.. code-block:: http

   POST /api/v1/lists/refresh HTTP/1.1
   Host: localhost:8080

**Response**

.. code-block:: json

   {
     "totalEntitiesLoaded": 15234,
     "totalDurationMs": 4523,
     "results": {
       "OFAC_SDN": {
         "status": "SUCCESS",
         "entityCount": 12847,
         "durationMs": 3200
       },
       "EU_CONSOLIDATED": {
         "status": "SKIPPED",
         "entityCount": 0,
         "durationMs": 0
       }
     }
   }

**Example**

.. code-block:: bash

   curl -X POST http://localhost:8080/api/v1/lists/refresh
