.. image:: _static/sieve-aml-logo.svg
   :alt: Sieve AML
   :align: center
   :width: 280px

.. raw:: html

   <br>

**Open-source sanctions screening platform.**

Sieve is a free, open alternative to commercial watchlist screening solutions.
It fetches publicly available sanctions lists, normalizes them into a unified
entity model, indexes them in memory, and exposes both a CLI and a REST API
for high-performance name screening.

.. grid:: 2

   .. grid-item-card:: 🚀 Getting Started
      :link: getting-started/index
      :link-type: doc

      Install, configure, and run your first screening query in minutes.

   .. grid-item-card:: 🔌 REST API
      :link: api/index
      :link-type: doc

      Full reference for the screening, lists, and health endpoints.

   .. grid-item-card:: 💻 CLI Reference
      :link: cli/index
      :link-type: doc

      Screen names, fetch lists, and export data from the command line.

   .. grid-item-card:: ⚙️ Algorithms
      :link: algorithms/index
      :link-type: doc

      How the matching pipeline works — normalization, n-grams, Jaro-Winkler.

.. grid:: 2

   .. grid-item-card:: 🏗️ Architecture
      :link: architecture/index
      :link-type: doc

      Module structure, data flow, and design decisions.

   .. grid-item-card:: 📊 Performance
      :link: performance/index
      :link-type: doc

      Benchmark results and optimization techniques.

Supported Sanctions Lists
-------------------------

.. list-table::
   :header-rows: 1
   :widths: 30 40 15

   * - List
     - Source
     - Status
   * - OFAC SDN
     - U.S. Treasury
     - ✅ Implemented
   * - EU Consolidated
     - European Commission
     - ✅ Implemented
   * - UN Consolidated
     - UN Security Council
     - ✅ Implemented
   * - UK HMT
     - HM Treasury
     - ✅ Implemented

.. toctree::
   :maxdepth: 2
   :hidden:

   getting-started/index
   api/index
   cli/index
   algorithms/index
   architecture/index
   performance/index
   java-api/index
