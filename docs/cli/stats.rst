stats
=====

Display index statistics — total entities, breakdown by source and type.

Synopsis
--------

.. code-block:: text

   sieve stats [-hV]

Options
-------

.. list-table::
   :header-rows: 1
   :widths: 30 70

   * - Option
     - Description
   * - ``-h, --help``
     - Show help message and exit
   * - ``-V, --version``
     - Print version info and exit

Example
-------

.. code-block:: bash

   java -jar sieve-cli.jar stats

Sample output:

.. code-block:: text

   Sieve Index Statistics
   ========================
   Total entities: 12,847
   Last updated:   2026-03-18T02:00:00Z

   By Source:
     OFAC SDN             12,847

   By Type:
     Individual            8,234
     Entity                4,200
     Vessel                  312
     Aircraft                101

.. note::

   If the index is empty, the command displays a hint to run ``sieve fetch`` first.
