Watchlist Ingestion
===================

Fetch, parse, normalize, and index sanctions lists from four global authorities.

Supported Lists
---------------

.. list-table::
   :header-rows: 1
   :widths: 20 20 10 15 35

   * - List
     - Source
     - Format
     - Update Frequency
     - Loader Class
   * - OFAC SDN
     - US Treasury
     - XML
     - Daily
     - ``dev.sieve.ingest.ofac.OfacSdnProvider``
   * - EU Consolidated
     - European Commission
     - XML
     - ~Weekly
     - ``dev.sieve.ingest.eu.EuConsolidatedProvider``
   * - UN Consolidated
     - UN Security Council
     - XML
     - As needed
     - ``dev.sieve.ingest.un.UnConsolidatedProvider``
   * - UK HMT
     - UK Gov
     - CSV
     - ~Weekly
     - ``dev.sieve.ingest.uk.UkHmtProvider``

Entity Model
------------

.. graphviz::

   digraph entity_model {
       rankdir=TB;
       node [shape=record, fontname="Helvetica", fontsize=10];

       entity [label="{SanctionedEntity|id : String\lentityType : EntityType\llistSource : ListSource\lprimaryName : NameInfo\lremarks : String\llastUpdated : Instant\l}"];
       alias  [label="{NameInfo|fullName : String\lfirstName : String\llastName : String\lnameType : NameType\lnameStrength : NameStrength\lscriptType : ScriptType\l}"];
       addr   [label="{Address|street : String\lcity : String\lstateOrProvince : String\lpostalCode : String\lcountry : String\lfullAddress : String\l}"];
       ident  [label="{Identifier|type : IdentifierType\lvalue : String\lcountry : String\l}"];
       prog   [label="{SanctionsProgram|code : String\l}"];

       entity -> alias  [label="aliases *", fontsize=9];
       entity -> addr   [label="addresses *", fontsize=9];
       entity -> ident  [label="identifiers *", fontsize=9];
       entity -> prog   [label="programs *", fontsize=9];
       entity -> alias  [label="primaryName 1", fontsize=9, style=dashed];
   }

Delta Detection
---------------

The ``IngestionOrchestrator`` tracks list metadata (ETag, last-fetched timestamp) per
source. On refresh, it compares incoming entities against the current index by entity
hash — only additions and modifications trigger index updates. The ``IngestionReport``
records per-source status, entity count, duration, and any errors.

Custom List Loader
------------------

Implement ``ListProvider`` to add your own sanctions list source:

.. code-block:: java

   public class MyCustomProvider implements ListProvider {
       @Override
       public ListSource source() { return ListSource.OFAC_SDN; }

       @Override
       public List<SanctionedEntity> fetch() {
           // Parse your data source and return normalized entities
           return List.of(/* ... */);
       }
   }

Register the provider with the orchestrator:

.. code-block:: java

   var orchestrator = new IngestionOrchestrator(List.of(
       new OfacSdnProvider(),
       new MyCustomProvider()
   ));
   orchestrator.ingest(entityIndex);

.. tip::

   Use ``HttpClientFactory`` from ``sieve-ingest`` for TLS-configured HTTP clients
   with connection pooling and timeout defaults tuned for government endpoints.
