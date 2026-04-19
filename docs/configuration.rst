Configuration Reference
=======================

All ``sieve.*`` properties for both server implementations.

Matching
--------

.. list-table::
   :header-rows: 1
   :widths: 35 10 10 45

   * - Property
     - Type
     - Default
     - Description
   * - ``sieve.screening.default-threshold``
     - ``double``
     - ``0.80``
     - Minimum score to include in results
   * - ``sieve.screening.max-results``
     - ``int``
     - ``50``
     - Maximum matches returned per query
   * - ``sieve.screening.max-batch-size``
     - ``int``
     - ``1000``
     - Maximum names per batch request

Lists
-----

.. list-table::
   :header-rows: 1
   :widths: 35 10 10 45

   * - Property
     - Type
     - Default
     - Description
   * - ``sieve.lists.ofac.enabled``
     - ``bool``
     - ``true``
     - Enable OFAC SDN list ingestion
   * - ``sieve.lists.ofac.url``
     - ``URI``
     - *(US Treasury)*
     - Override OFAC SDN XML download URL
   * - ``sieve.lists.eu.enabled``
     - ``bool``
     - ``false``
     - Enable EU Consolidated list ingestion
   * - ``sieve.lists.eu.url``
     - ``URI``
     - *(EU Commission)*
     - Override EU XML download URL
   * - ``sieve.lists.un.enabled``
     - ``bool``
     - ``false``
     - Enable UN Consolidated list ingestion
   * - ``sieve.lists.un.url``
     - ``URI``
     - *(UN Security Council)*
     - Override UN XML download URL
   * - ``sieve.lists.uk.enabled``
     - ``bool``
     - ``false``
     - Enable UK HMT list ingestion
   * - ``sieve.lists.uk.url``
     - ``URI``
     - *(UK Gov)*
     - Override UK CSV download URL

Address
-------

.. list-table::
   :header-rows: 1
   :widths: 35 10 10 45

   * - Property
     - Type
     - Default
     - Description
   * - ``sieve.address.libpostal-enabled``
     - ``bool``
     - ``true``
     - Use libpostal JNI for address parsing
   * - ``sieve.address.data-dir``
     - ``Path``
     - *(system)*
     - Override libpostal data directory

Audit
-----

.. list-table::
   :header-rows: 1
   :widths: 35 10 10 45

   * - Property
     - Type
     - Default
     - Description
   * - ``sieve.audit.enabled``
     - ``bool``
     - ``true``
     - Emit audit events after each screening
   * - ``sieve.audit.emitter``
     - ``String``
     - ``logging``
     - Emitter type: ``logging``, ``noop``, or custom bean name

Batch
-----

.. list-table::
   :header-rows: 1
   :widths: 35 10 10 45

   * - Property
     - Type
     - Default
     - Description
   * - ``sieve.batch.max-size``
     - ``int``
     - ``1000``
     - Hard limit on batch request size
   * - ``sieve.batch.timeout-ms``
     - ``long``
     - ``30000``
     - Maximum wall-clock time for a batch request

Vert.x Server (CLI / Environment)
----------------------------------

The Vert.x server reads configuration from CLI flags and environment variables
(CLI takes precedence over env vars, which take precedence over defaults).

.. list-table::
   :header-rows: 1
   :widths: 20 25 10 45

   * - Flag
     - Env Var
     - Default
     - Description
   * - ``--port``
     - ``SIEVE_PORT``
     - ``8080``
     - HTTP listen port
   * - ``--threshold``
     - ``SIEVE_THRESHOLD``
     - ``0.80``
     - Default match threshold
   * - ``--max-results``
     - ``SIEVE_MAX_RESULTS``
     - ``50``
     - Max results per query
   * - ``--max-batch-size``
     - ``SIEVE_MAX_BATCH_SIZE``
     - ``1000``
     - Max names per batch
   * - ``--ofac``
     - ``SIEVE_OFAC_ENABLED``
     - ``true``
     - Enable OFAC SDN
   * - ``--eu``
     - ``SIEVE_EU_ENABLED``
     - ``false``
     - Enable EU Consolidated
   * - ``--un``
     - ``SIEVE_UN_ENABLED``
     - ``false``
     - Enable UN Consolidated
   * - ``--uk``
     - ``SIEVE_UK_ENABLED``
     - ``false``
     - Enable UK HMT

Spring Boot Auto-Configuration
------------------------------

The Spring Boot server auto-registers all beans (``EntityIndex``, ``MatchEngine``,
``AddressMatchService``, ``IngestionOrchestrator``) based on ``@ConfigurationProperties``
bound to the ``sieve.*`` namespace. See :doc:`spring-integration` for details.
