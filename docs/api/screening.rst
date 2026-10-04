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
     - Filter by entity type: ``INDIVIDUAL``, ``ENTITY``, ``COMPANY``, ``ORGANIZATION``,
       ``VESSEL``, ``AIRCRAFT``, ``CRYPTO_WALLET``, ``SECURITY``. ``ENTITY`` also returns
       companies and organisations, and ``COMPANY`` or ``ORGANIZATION`` also return generic
       entities, because most lists do not say which kind a legal entity is
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
           "id": "ofac-sdn-36735",
           "entityType": "INDIVIDUAL",
           "listSource": "OFAC_SDN",
           "primaryName": "PUTIN, Vladimir Vladimirovich",
           "aliases": ["Vladimir PUTIN", "Владимир Путин"],
           "nationalities": ["Russia"],
           "programs": ["RUSSIA-EO14024"],
           "topics": ["SANCTION"],
           "remarks": "President of the Russian Federation",
           "lastUpdated": "2024-01-15T00:00:00Z",
           "firstSeen": "2026-03-01T02:00:00Z",
           "lastSeen": "2026-03-18T02:00:00Z",
           "provenance": [
             {
               "kind": "NAME",
               "value": "Vladimir PUTIN",
               "source": "OFAC_SDN",
               "sourceUrl": "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML",
               "firstSeen": "2026-03-01T02:00:00Z",
               "lastSeen": "2026-03-18T02:00:00Z"
             }
           ]
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
   * - ``results[].entity.provenance[]``
     - array
     - Where and when each value of the entity was seen: ``kind`` (``NAME``, ``BIRTH_DATE``,
       ``IDENTIFIER``, ``ADDRESS``, ``PROGRAM`` and so on), ``value`` (an identifier reads
       ``PASSPORT:123456``), the ``source`` list and ``sourceUrl`` it was read from, and when it was
       ``firstSeen`` and ``lastSeen``. First seen is kept across refreshes while the value stays on
       the list; it starts when the server first loads the list
   * - ``results[].entity.firstSeen`` / ``lastSeen``
     - ISO 8601
     - Earliest first seen and latest last seen over the entity's values
   * - ``results[].score``
     - number
     - Match confidence score (0.0–1.0)
   * - ``results[].matchedField``
     - string
     - Which field matched (``primaryName``, ``alias[N]`` or ``nameComponent[N]``). A match on a
       single name component, or a one-word query against a longer name, covers only part of the
       name and its score is multiplied by 0.75
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
