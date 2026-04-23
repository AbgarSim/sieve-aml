screen
======

Screen a name against loaded sanctions lists using fuzzy and exact matching.

Synopsis
--------

.. code-block:: text

   sieve screen [-hV] [-l=<list>] [-n=<maxResults>] [-t=<threshold>] <name>

Arguments
---------

.. list-table::
   :header-rows: 1
   :widths: 20 80

   * - Argument
     - Description
   * - ``<name>``
     - Name to screen (required). Wrap in quotes if it contains spaces.

Options
-------

.. list-table::
   :header-rows: 1
   :widths: 35 15 50

   * - Option
     - Default
     - Description
   * - ``-t, --threshold=<threshold>``
     - ``0.80``
     - Minimum match score (0.0–1.0)
   * - ``-l, --list=<list>``
     - all
     - Screen against a specific list (e.g., ``ofac-sdn``)
   * - ``-n, --max-results=<n>``
     - ``20``
     - Maximum number of results to display
   * - ``-h, --help``
     -
     - Show help message and exit

Exit Codes
----------

The ``screen`` command uses CI/CD-friendly exit codes:

- **0** — no match found (name is clear)
- **1** — match found (name is on a sanctions list)

This makes it easy to integrate into automated compliance pipelines:

.. code-block:: bash

   java -jar sieve-cli.jar screen "John Doe" --threshold=0.90
   if [ $? -eq 1 ]; then
     echo "⚠️  Potential sanctions match detected"
   fi

Examples
--------

Basic screening:

.. code-block:: bash

   java -jar sieve-cli.jar screen "Vladimir Putin"

With a higher threshold:

.. code-block:: bash

   java -jar sieve-cli.jar screen "Vladimir Putin" --threshold=0.90

Against a specific list:

.. code-block:: bash

   java -jar sieve-cli.jar screen "Vladimir Putin" --list=ofac-sdn

Sample output:

.. code-block:: text

   2 match(es) found for "Vladimir Putin" (threshold=0.80)

     Score   Name                                      Source           Type          Algorithm
     ---------------------------------------------------------------------------------------------------------------
     0.9412  PUTIN, Vladimir Vladimirovich              OFAC_SDN        INDIVIDUAL    JARO_WINKLER
     0.8823  PUTIN, Vladimir                            OFAC_SDN        INDIVIDUAL    JARO_WINKLER

.. note::

   If the index is empty, the ``screen`` command automatically fetches lists
   before screening. Use ``sieve fetch`` to pre-populate the index.
