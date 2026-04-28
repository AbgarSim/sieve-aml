export
======

Export loaded entities in JSON format to stdout.

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
     - Output format (currently only ``json`` is supported)
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
       "id": "36735",
       "entityType": "INDIVIDUAL",
       "listSource": "OFAC_SDN",
       "primaryName": "PUTIN, Vladimir Vladimirovich",
       "aliases": ["Vladimir PUTIN"],
       "programs": ["RUSSIA-EO14024"]
     }
   ]

.. note::

   The index must be populated before exporting. Run ``sieve fetch`` first
   if the index is empty.
