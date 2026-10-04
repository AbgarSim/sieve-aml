export
======

Export loaded entities to stdout, as a JSON summary or in the FollowTheMoney entity format.

Synopsis
--------

.. code-block:: text

   sieve export [-hV] [-f=<format>]

Options
-------

.. list-table::
   :header-rows: 1
   :widths: 30 15 55

   * - Option
     - Default
     - Description
   * - ``-f, --format=<format>``
     - ``json``
     - ``json`` for a summary array, or ``ftm`` for FollowTheMoney JSON lines
   * - ``-h, --help``
     -
     - Show help message and exit
   * - ``-V, --version``
     -
     - Print version info and exit

Examples
--------

Export all entities to a file:

.. code-block:: bash

   java -jar sieve-cli.jar export > entities.json

Pipe to ``jq`` for pretty-printing:

.. code-block:: bash

   java -jar sieve-cli.jar export | jq '.[0]'

Sample output:

.. code-block:: json

   [
     {
       "id": "ofac-sdn-36735",
       "entityType": "INDIVIDUAL",
       "listSource": "OFAC_SDN",
       "primaryName": "PUTIN, Vladimir Vladimirovich",
       "aliases": ["Vladimir PUTIN"],
       "topics": ["SANCTION"],
       "programs": ["RUSSIA-EO14024"]
     }
   ]

FollowTheMoney format
---------------------

``--format ftm`` writes one JSON object per line in the open FollowTheMoney entity format, so other
screening and investigation tools can load it. Each entity becomes an object of its kind's schema
(``Person``, ``LegalEntity``, ``Company``, ``Organization``, ``Vessel``, ``Airplane``,
``CryptoWallet`` or ``Security``); relations become ``Ownership``, ``Directorship``, ``Family``,
``Associate``, ``UnknownLink`` or ``Occupancy`` objects, and a sanctioned entity's programs one
``Sanction`` object. Risk topics use the format's topic codes (``sanction``, ``role.pep`` and so
on). ``dev.sieve.ingest.ftm.FtmReader`` reads the same format back.

.. code-block:: bash

   java -jar sieve-cli.jar export --format ftm > entities.ftm.json

.. code-block:: json

   {"id":"ofac-sdn-36735","schema":"Person","properties":{"name":["PUTIN, Vladimir Vladimirovich"],"alias":["Vladimir PUTIN"],"topics":["sanction"],"programId":["RUSSIA-EO14024"]},"datasets":["ofac_sdn"]}
   {"id":"ofac-sdn-36735-sanction","schema":"Sanction","properties":{"entity":["ofac-sdn-36735"],"authority":["OFAC SDN"],"programId":["RUSSIA-EO14024"]},"datasets":["ofac_sdn"]}

.. note::

   The index must be populated before exporting. Run ``sieve fetch`` first
   if the index is empty.
