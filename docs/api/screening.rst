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
   * - ``dateOfBirth``
     - string
     - No
     - Date of birth: ``YYYY``, ``YYYY-MM`` or ``YYYY-MM-DD`` (``DD.MM.YYYY`` is also read)
   * - ``placeOfBirth``
     - string
     - No
     - Place of birth, such as a city
   * - ``country``
     - string
     - No
     - Country of nationality, citizenship, residence or registration, as a name or ISO code.
       Compared with the record's nationalities, citizenships and address countries
   * - ``address``
     - string
     - No
     - Address, free text
   * - ``identifiers``
     - string[]
     - No
     - Identifier values of any type: passport, national id, tax id, LEI, registration number.
       Case, spaces and punctuation are ignored
   * - ``gender``
     - string
     - No
     - ``male``, ``female`` or ``other``

Facts beyond the name
^^^^^^^^^^^^^^^^^^^^^

A name alone matches many people: on real list data, made-up names that are on no list reach
0.80 most of the time. The optional fields above let a screening say more about the party, and
each one given is weighed against the matched record:

- **agreement raises the score**, moving it part of the way towards 1.0;
- **a clear difference lowers it**, taking a share off it;
- **a fact the record does not state changes nothing.**

.. list-table::
   :header-rows: 1
   :widths: 20 40 40

   * - Field
     - Agrees (share of the gap to 1.0 closed)
     - Differs (share of the score removed)
   * - ``dateOfBirth``
     - same day 0.40; same year (or month) 0.25; one year off or day and month swapped 0.10
     - 0.30
   * - ``identifiers``
     - 0.60
     - 0.05 (the record lists other identifiers)
   * - ``country``
     - 0.15
     - 0.15
   * - ``placeOfBirth``
     - 0.15 (all words found); 0.075 (some)
     - 0.05
   * - ``address``
     - 0.15 (all words found); 0.05 (some)
     - 0.05
   * - ``gender``
     - 0.05
     - 0.25

Many lists give only a birth year, which Sieve stores as 1 January of that year, so a record date
of 1 January agrees with any date in that year. A query of ``"1970"`` agrees with any record date
in 1970.

The score stays between 0 and 1 and the threshold keeps its meaning. A name that scores 0.95 on its
own falls to 0.67 when the date of birth differs, and a name at 0.80 rises to 0.92 when an
identifier matches. Facts can lift a name match up to the threshold only from a name score of
0.70 or more. A request with none of these fields is screened by name exactly as before, and its
response carries no new fields.

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
   * - ``results[].nameScore``
     - number
     - Score of the name alone, before the facts beyond the name were weighed. Only present when
       the request gave one of them
   * - ``results[].evidence[]``
     - array
     - How each fact beyond the name compared with the record: ``field`` (``dateOfBirth``,
       ``identifier``, ``country``, ``placeOfBirth``, ``address``, ``gender``), ``outcome``
       (``MATCH``, ``PARTIAL``, ``MISMATCH`` or ``NOT_ON_RECORD``) and ``recordValue``, the
       record's value that decided it. Only present when the request gave one of them

With facts beyond the name, a result reads:

.. code-block:: json

   {
     "entity": { "id": "ofac-sdn-36735", "primaryName": "PUTIN, Vladimir Vladimirovich" },
     "score": 0.9647,
     "matchedField": "alias[0]",
     "matchAlgorithm": "JARO_WINKLER",
     "nameScore": 0.9412,
     "evidence": [
       { "field": "dateOfBirth", "outcome": "MATCH", "recordValue": "1952-10-07" },
       { "field": "country", "outcome": "MATCH", "recordValue": "RU" }
     ]
   }

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
