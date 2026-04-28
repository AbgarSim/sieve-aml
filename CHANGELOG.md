# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
