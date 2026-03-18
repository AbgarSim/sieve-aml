Screening
=========

Screen names against loaded sanctions lists using fuzzy and exact matching.

.. contents:: On this page
   :local:
   :depth: 2

POST /api/v1/screen
--------------------

Screen a name against all loaded sanctions lists.

**Request**

.. code-block:: http

   POST /api/v1/screen HTTP/1.1
   Host: localhost:8080
   Content-Type: application/json

   {
     "name": "Vladimir Putin",
     "entityType": "INDIVIDUAL",
     "sources": ["OFAC_SDN"],
     "threshold": 0.80
   }

**Request Body Fields**

.. list-table::
   :header-rows: 1
   :widths: 20 12 10 58

   * - Field
     - Type
     - Required
     - Description
   * - ``name``
     - string
     - ✅ Yes
     - Name to screen against sanctions lists
   * - ``entityType``
     - string
     - No
     - Filter by entity type: ``INDIVIDUAL``, ``ENTITY``, ``VESSEL``, ``AIRCRAFT``
   * - ``sources``
     - string[]
     - No
     - Filter by list sources: ``OFAC_SDN``, ``EU_CONSOLIDATED``, ``UN_CONSOLIDATED``, ``UK_HMT``
   * - ``threshold``
     - number
     - No
     - Minimum match score (0.0–1.0). Defaults to server-configured value (0.80)

**Response**

.. code-block:: json

   {
     "query": "Vladimir Putin",
     "totalMatches": 2,
     "screenedAt": "2026-03-18T12:00:00Z",
     "results": [
       {
         "entity": {
           "id": "36735",
           "entityType": "INDIVIDUAL",
           "listSource": "OFAC_SDN",
           "primaryName": "PUTIN, Vladimir Vladimirovich",
           "aliases": ["Vladimir PUTIN", "Владимир Путин"],
           "nationalities": ["Russia"],
           "programs": ["RUSSIA-EO14024"],
           "remarks": "President of the Russian Federation",
           "lastUpdated": "2024-01-15T00:00:00Z"
         },
         "score": 0.9412,
         "matchedField": "alias[0]",
         "matchAlgorithm": "JARO_WINKLER"
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
   * - ``query``
     - string
     - The original query name
   * - ``totalMatches``
     - integer
     - Number of matches returned
   * - ``screenedAt``
     - ISO 8601
     - Timestamp of the screening
   * - ``results[]``
     - array
     - Match results sorted by score descending
   * - ``results[].score``
     - number
     - Match confidence score (0.0–1.0)
   * - ``results[].matchedField``
     - string
     - Which field matched (``primaryName`` or ``alias[N]``)
   * - ``results[].matchAlgorithm``
     - string
     - Algorithm used: ``EXACT`` or ``JARO_WINKLER``

**Examples**

.. tab-set::

   .. tab-item:: curl

      .. code-block:: bash

         curl -X POST http://localhost:8080/api/v1/screen \
           -H "Content-Type: application/json" \
           -d '{"name": "John Doe", "threshold": 0.85}'

   .. tab-item:: Python

      .. code-block:: python

         import requests

         response = requests.post(
             "http://localhost:8080/api/v1/screen",
             json={"name": "John Doe", "threshold": 0.85},
         )
         results = response.json()

   .. tab-item:: Java

      .. code-block:: java

         HttpClient client = HttpClient.newHttpClient();
         String body = """
             {"name": "John Doe", "threshold": 0.85}
             """;
         HttpRequest request = HttpRequest.newBuilder()
                 .uri(URI.create("http://localhost:8080/api/v1/screen"))
                 .header("Content-Type", "application/json")
                 .POST(HttpRequest.BodyPublishers.ofString(body))
                 .build();
         HttpResponse<String> response =
                 client.send(request, HttpResponse.BodyHandlers.ofString());

**Status Codes**

.. list-table::
   :header-rows: 1
   :widths: 15 85

   * - Code
     - Description
   * - ``200``
     - Screening completed successfully
   * - ``400``
     - Invalid request (e.g., blank name, threshold out of range)
