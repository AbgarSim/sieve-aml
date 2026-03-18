fetch
=====

Fetch sanctions lists from their official sources and load them into the
in-memory index.

Synopsis
--------

.. code-block:: text

   sieve fetch [-hV] [-l=<list>]

Options
-------

.. list-table::
   :header-rows: 1
   :widths: 30 70

   * - Option
     - Description
   * - ``-l, --list=<list>``
     - Specific list to fetch (e.g., ``ofac-sdn``). Fetches all enabled lists if omitted.
   * - ``-h, --help``
     - Show help message and exit
   * - ``-V, --version``
     - Print version info and exit

Examples
--------

Fetch all enabled lists:

.. code-block:: bash

   java -jar sieve-cli.jar fetch

Fetch a specific list:

.. code-block:: bash

   java -jar sieve-cli.jar fetch --list=ofac-sdn

Sample output:

.. code-block:: text

   Fetching sanctions lists...

     ✓ OFAC SDN — SUCCESS (12,847 entities, 3200ms)
     ⊘ EU Consolidated — SKIPPED (0 entities, 0ms)
     ⊘ UN Consolidated — SKIPPED (0 entities, 0ms)
     ⊘ UK HMT — SKIPPED (0 entities, 0ms)

   Total: 12,847 entities loaded in 3200ms
