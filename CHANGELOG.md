# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The repository was recreated on 2026-04-28. Commit history before that date was
reconstructed afterwards from the existing code, so its dates are approximate.

## [Unreleased]

### Added
- Multi-module Maven project structure (core, ingest, match, server, cli)
- Unified domain model with Java 21 records (`SanctionedEntity`, `NameInfo`, `Address`, `Identifier`, `SanctionsProgram`)
- Enums with `displayName()` and `fromString()`: `EntityType`, `ListSource`, `NameType`, `NameStrength`, `ScriptType`, `IdentifierType`
- In-memory entity index (`InMemoryEntityIndex`) with thread-safe concurrent access
- `ListProvider` SPI for sanctions list fetchers
- OFAC SDN provider with StAX streaming XML parser and ETag-based delta detection
- Stub providers for EU Consolidated, UN Consolidated, and UK HMT lists
- `IngestionOrchestrator` for coordinated multi-source ingestion with reporting
- Jaro-Winkler string similarity algorithm (implemented from scratch)
- Exact match engine and fuzzy match engine
- Composite match engine with deduplication and best-score selection
- Spring Boot 3.3 REST API with screening, list management, health, and refresh endpoints
- RFC 7807 Problem Detail error responses via `@RestControllerAdvice`
- `@ConfigurationProperties` with Bean Validation
- Picocli CLI with `fetch`, `screen`, `stats`, and `export` commands
- Comprehensive test suite with JUnit 5, AssertJ, Mockito, and parameterized tests
- OFAC SDN test sample XML for offline testing
- Risk topics (`RiskTopic`: sanction, sanction-linked, export control, debarment, PEP, relative or close associate, crime, wanted, state-owned) and relations between entities (`Relation`, `RelationType`) on `SanctionedEntity`; entries from today's sanctions lists carry the `SANCTION` topic, and API responses and exports list each entity's `topics`
- US BIS Entity List (`US_BIS_ENTITY`, ids `bis-el-`) and Military End-User List (`US_BIS_MEU`, ids `bis-meu-`) as their own sources, split out of the Consolidated Screening List and tagged `EXPORT_CONTROL`; their licence requirement, licence policy and Federal Register notice are kept in remarks
- Argentina RePET (`AR_REPET`, ids `ar-person-` and `ar-entity-`): the Ministry of Justice terrorism registry, read from its people and entities JSON files
- India MHA (`IN_MHA`, ids `in-mha-`): individual terrorists designated under the Fourth Schedule of India's Unlawful Activities (Prevention) Act, read from the ministry's web page
- US FBI Wanted (`US_FBI_WANTED`, ids `fbi-`): people on open FBI wanted posters from the FBI's public API, tagged `WANTED`; missing-person, victim, kidnapping, unidentified-person, information-request and closed posters are left out
- EU Most Wanted (`EU_MOST_WANTED`, ids `eu-mw-`): fugitives EU member states publish through Europol's most wanted site, tagged `WANTED`, with the crime, requesting country and case state, plus date of birth and nationality from each poster page; solved cases are left out
- World Bank Debarred (`WB_DEBARRED`, ids `wb-`): firms and individuals the World Bank Group has debarred, tagged `DEBARMENT`, with address, grounds and ineligibility period; the Bank has no download, so the list is read from the API behind its debarred-firms page using the key that page publishes, and debarments that have ended are left out
- Wikidata PEPs (`WIKIDATA_PEP`, ids `wd-`): politically exposed persons from Wikidata, tagged `PEP`. Living holders of national offices (heads of state and government and ministers as tier 1, members of parliament and judges as tier 2) who are in office or left within five years, with each office, its country, tier and term in remarks; queried country by country to stay within the query service's limits
- The dashboard snapshot never writes PEP and RCA records or search-index entries; it counts them per list, in the history and as `pepEntities` in the overview, and keeps them out of the headline totals, country map and top programs

### Changed
- Entity ids from OFAC SDN, UN, UK HMT and EU Consolidated now carry a source prefix (`ofac-sdn-`, `un-`, `uk-`, `eu-`), like every other source, so ids from different lists cannot collide
- A list refresh now replaces that source's entities, so entries the source has delisted are removed instead of staying until restart; refresh results report a `removedCount`, and an empty fetch keeps the previous entities and reports the source as failed
- Name caches and the n-gram index rebuild whenever the index content changes, not only when its size changes
- A match that covers only part of a listed name (a lone first or family name, or a one-word query against a longer name) is discounted by 0.75 in the exact, fuzzy and phonetic engines, so it no longer scores 1.0 and falls below the default 0.80 threshold
