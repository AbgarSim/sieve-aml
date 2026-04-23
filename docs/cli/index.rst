CLI Reference
=============

Sieve includes a standalone command-line interface built with
`Picocli <https://picocli.info/>`_. It does **not** depend on Spring Boot
and runs as a lightweight Java application.

.. code-block:: text

   Usage: sieve [-hV] [COMMAND]
   Open-source sanctions screening platform

   Options:
     -h, --help      Show help message and exit
     -V, --version   Print version info and exit

   Commands:
     fetch    Fetch sanctions lists and load into the index
     screen   Screen a name against sanctions lists
     stats    Show index statistics
     export   Export loaded entities
     help     Display help for a command

**Exit Codes**

.. list-table::
   :header-rows: 1
   :widths: 15 85

   * - Code
     - Meaning
   * - ``0``
     - Success / no match found
   * - ``1``
     - Match found (``screen`` command only)
   * - ``2``
     - Error

.. toctree::
   :maxdepth: 2

   fetch
   screen
   stats
   export
