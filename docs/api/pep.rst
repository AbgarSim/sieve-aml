PEP functions
=============

The EU list of prominent public functions: the positions each member state, and the
Union for its own institutions and bodies, lists under Article 20a of Directive (EU)
2015/849 as making their holder a politically exposed person. Sieve ships the list the
Official Journal published as C/2023/724 on 10 November 2023 as a reference table of
2,161 functions in 28 jurisdictions. Each function carries the directive category its
list files it under (a point of Article 3(9), ``a`` to ``h``), the heading it appears
under and, for a post in an international organisation, the organisation.

The Wikidata PEP list uses the same table: every PEP's ``listingReasons`` cite the
directive category of each office held and, when the office's state is in the list,
the state's own entry for it.

.. contents:: On this page
   :local:
   :depth: 2

GET /api/v1/pep/functions
-------------------------

Returns the Official Journal document the table is read from and how many functions
each jurisdiction lists.

**Request**

.. code-block:: http

   GET /api/v1/pep/functions HTTP/1.1
   Host: localhost:8080

**Response**

.. code-block:: json

   {
     "title": "Prominent public functions at national level, at the level of International Organisations and at the level of the European Union Institutions and Bodies",
     "reference": "C/2023/724",
     "published": "2023-11-10",
     "eli": "http://data.europa.eu/eli/C/2023/724/oj",
     "total": 2161,
     "jurisdictions": [
       { "jurisdiction": "AT", "count": 29 },
       { "jurisdiction": "BE", "count": 113 },
       { "jurisdiction": "EU", "count": 10 }
     ]
   }

**Response Fields**

.. list-table::
   :header-rows: 1
   :widths: 25 12 63

   * - Field
     - Type
     - Description
   * - ``title``
     - string
     - Title of the Official Journal document
   * - ``reference``
     - string
     - Official Journal number
   * - ``published``
     - ISO 8601 date
     - Publication date
   * - ``eli``
     - string
     - European Legislation Identifier of the document
   * - ``total``
     - integer
     - Number of functions in the table
   * - ``jurisdictions[].jurisdiction``
     - string
     - ISO 3166-1 alpha-2 code of a member state, or ``EU`` for the Union's institutions
       and bodies
   * - ``jurisdictions[].count``
     - integer
     - Number of functions that jurisdiction lists

GET /api/v1/pep/functions/{jurisdiction}
----------------------------------------

Returns the functions one jurisdiction lists, in list order.

**Request**

.. code-block:: http

   GET /api/v1/pep/functions/DE?category=a HTTP/1.1
   Host: localhost:8080

**Path Parameters**

.. list-table::
   :header-rows: 1
   :widths: 25 12 63

   * - Parameter
     - Type
     - Description
   * - ``jurisdiction``
     - string
     - ISO 3166-1 alpha-2 code of a member state, or ``EU``; case does not matter

**Query Parameters**

.. list-table::
   :header-rows: 1
   :widths: 25 12 63

   * - Parameter
     - Type
     - Description
   * - ``category``
     - string
     - Optional. A point of Article 3(9) of Directive (EU) 2015/849, ``a`` to ``h``, to
       return only the functions filed under it

**Response**

.. code-block:: json

   {
     "jurisdiction": "DE",
     "reference": "C/2023/724",
     "count": 4,
     "functions": [
       {
         "category": "a",
         "heading": "Head of State",
         "function": "Federal President (Bundespräsident)"
       },
       {
         "category": "a",
         "heading": "Head of Government",
         "function": "Federal Chancellor (Bundeskanzler)"
       },
       {
         "category": "a",
         "heading": "Ministers",
         "function": "Federal ministers"
       },
       {
         "category": "a",
         "heading": "Deputy ministers and state secretaries",
         "function": "Parliamentary state secretary, minister of state, state secretary"
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
   * - ``jurisdiction``
     - string
     - The jurisdiction's code, upper-cased
   * - ``reference``
     - string
     - Official Journal number the list is read from
   * - ``count``
     - integer
     - Number of functions returned
   * - ``functions[].category``
     - string
     - Point of Article 3(9) the list files the function under, ``a`` to ``h``; absent
       when the list adds the function outside the directive's categories, as the
       national lists do for regional and local offices and heads of agencies
   * - ``functions[].heading``
     - string
     - Heading the function appears under, as the list words it; absent when it has none
   * - ``functions[].function``
     - string
     - The function, as the list words it
   * - ``functions[].organisation``
     - string
     - For a post in an international organisation, the organisation; absent for a
       national function

**Errors**

.. list-table::
   :header-rows: 1
   :widths: 12 88

   * - Status
     - When
   * - ``404``
     - The jurisdiction has no list in the Official Journal document
   * - ``400``
     - ``category`` is not a point ``a`` to ``h``

The categories
--------------

The points of Article 3(9) of Directive (EU) 2015/849, as the table and the Wikidata
PEP list's ``listingReasons`` cite them.

.. list-table::
   :header-rows: 1
   :widths: 10 90

   * - Point
     - Prominent public functions
   * - ``a``
     - heads of State, heads of government, ministers and deputy or assistant ministers
   * - ``b``
     - members of parliament or of similar legislative bodies
   * - ``c``
     - members of the governing bodies of political parties
   * - ``d``
     - members of supreme courts, of constitutional courts or of other high-level
       judicial bodies
   * - ``e``
     - members of courts of auditors or of the boards of central banks
   * - ``f``
     - ambassadors, chargés d'affaires and high-ranking officers in the armed forces
   * - ``g``
     - members of the administrative, management or supervisory bodies of State-owned
       enterprises
   * - ``h``
     - directors, deputy directors and members of the board or equivalent function of an
       international organisation
