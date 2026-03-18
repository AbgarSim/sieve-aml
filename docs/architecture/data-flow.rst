Data Flow
=========

This page describes how data flows through Sieve — from sanctions list
ingestion through to screening query responses.

Ingestion Flow
--------------

.. mermaid::

   sequenceDiagram
       participant O as IngestionOrchestrator
       participant P as ListProvider (e.g., OFAC)
       participant X as XML Source
       participant I as EntityIndex

       O->>P: fetch()
       P->>X: HTTP GET (with ETag)
       X-->>P: XML response
       P->>P: StAX streaming parse
       P-->>O: List<SanctionedEntity>
       O->>I: addAll(entities)
       Note over I: ConcurrentHashMap<br/>keyed by entity ID

Each ``ListProvider`` implementation:

1. **Fetches** the XML document from the official source URL
2. **Parses** it with StAX streaming (constant memory, handles large files)
3. **Maps** XML elements to the unified ``SanctionedEntity`` domain model
4. **Returns** a list of entities to the orchestrator

The ``IngestionOrchestrator`` coordinates multiple providers, collects
results, and produces an ``IngestionReport`` with per-source statistics.

Screening Flow
--------------

.. mermaid::

   sequenceDiagram
       participant C as Client
       participant SC as ScreeningController
       participant CE as CompositeMatchEngine
       participant NC as NormalizedNameCache
       participant NG as NgramIndex
       participant JW as JaroWinkler

       C->>SC: POST /api/v1/screen
       SC->>CE: screen(request, index)
       CE->>CE: ExactMatchEngine.screen()
       CE->>NC: ensureBuilt(index)
       CE->>NG: ensureBuilt(index, nameCache)
       CE->>NG: candidates("vladimir putin")
       NG-->>CE: ~50 entities
       loop For each candidate
           CE->>NC: get(entity)
           NC-->>CE: NormalizedEntry
           CE->>JW: similarityWithThreshold()
           JW-->>CE: score
       end
       CE->>CE: FuzzyMatchEngine.screen()
       Note over CE: Same flow as above
       CE->>CE: Deduplicate by entity ID
       CE-->>SC: List<MatchResult>
       SC-->>C: 200 OK + JSON response

Entity Model
------------

The unified domain model represents sanctioned entities from all list sources:

.. mermaid::

   classDiagram
       class SanctionedEntity {
           +String id
           +EntityType entityType
           +ListSource listSource
           +NameInfo primaryName
           +List~NameInfo~ aliases
           +List~Address~ addresses
           +List~Identifier~ identifiers
           +List~SanctionsProgram~ programs
           +List~String~ nationalities
           +String remarks
           +Instant lastUpdated
       }

       class NameInfo {
           +String fullName
           +String firstName
           +String lastName
           +NameType nameType
           +NameStrength nameStrength
           +ScriptType scriptType
       }

       class Address {
           +String street
           +String city
           +String stateOrProvince
           +String postalCode
           +String country
           +String fullAddress
       }

       class Identifier {
           +IdentifierType type
           +String value
           +String country
           +String issueDate
           +String expirationDate
       }

       class SanctionsProgram {
           +String code
           +String description
       }

       SanctionedEntity --> NameInfo : primaryName
       SanctionedEntity --> "0..*" NameInfo : aliases
       SanctionedEntity --> "0..*" Address : addresses
       SanctionedEntity --> "0..*" Identifier : identifiers
       SanctionedEntity --> "0..*" SanctionsProgram : programs

All domain objects are immutable **Java 21 records**.

Index Architecture
------------------

.. mermaid::

   flowchart TD
       subgraph "EntityIndex (interface)"
           IM["InMemoryEntityIndex<br/><small>ConcurrentHashMap</small>"]
           JPA["JpaEntityIndex<br/><small>PostgreSQL + Hibernate</small>"]
       end

       subgraph "Acceleration Structures"
           NC["NormalizedNameCache<br/><small>Pre-normalized names</small>"]
           NG["NgramIndex<br/><small>Trigram inverted index</small>"]
       end

       IM --> NC
       IM --> NG
       JPA --> NC
       JPA --> NG

The ``EntityIndex`` interface is implemented by:

- **InMemoryEntityIndex** — default, uses ``ConcurrentHashMap`` with secondary
  index by ``ListSource``
- **JpaEntityIndex** — PostgreSQL persistence with Hibernate, activated via the
  ``postgres`` Spring profile

Both implementations work with the same acceleration structures
(``NormalizedNameCache`` and ``NgramIndex``) for matching.
