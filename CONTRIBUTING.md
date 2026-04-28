# Contributing to Sieve

Thank you for your interest in contributing to Sieve! This document provides guidelines and instructions for contributing.

## Prerequisites

- **Java 21+** — [Eclipse Temurin](https://adoptium.net/) or equivalent
- **Maven 3.9+** — [Download](https://maven.apache.org/download.cgi)

## Building

```bash
# Full build with tests
mvn clean verify

# Compile only (fast feedback)
mvn clean compile -T4

# Run tests only
mvn test

# Run integration tests
mvn verify -DskipUnitTests
```

## Code Style

This project uses **Google Java Format** enforced via the Spotless Maven plugin.

```bash
# Check formatting
mvn spotless:check

# Apply formatting
mvn spotless:apply
```

Key conventions:
- **AOSP variant** of Google Java Format (4-space indent)
- No raw types or unchecked casts without `@SuppressWarnings` and a justification comment
- No `null` returns from public methods — use `Optional` where absence is valid
- Constructor injection only — no `@Autowired` on fields
- Package-private by default; only `public` what's part of the module API
- Records for all value objects
- `Objects.requireNonNull()` in constructors and compact record constructors

## Project Structure

```
sieve/
├── sieve-core/     # Domain model, index abstraction, match SPI (zero dependencies)
├── sieve-ingest/   # List fetchers and parsers
├── sieve-match/    # Matching engine implementations
├── sieve-server/   # Spring Boot REST API
├── sieve-cli/      # Picocli command-line interface
└── pom.xml         # Parent POM
```

### Module Dependency Rules

- `sieve-core` has **zero compile dependencies** beyond `slf4j-api`
- Dependencies flow in one direction: `cli/server → match → ingest → core`
- No dependency cycles

## Adding a New List Provider

1. Create a new package under `sieve-ingest`: `dev.sieve.ingest.<source>`
2. Implement `ListProvider` for your source
3. Add a test resource XML file with sample data
4. Write unit tests covering the full parse pipeline
5. Register the provider in `SieveConfiguration` (server) and `CliContext` (CLI)
6. Add configuration properties in `application.yml`

## Testing

- **JUnit 5 + AssertJ** for all assertions
- **Mockito** for mocking
- Unit tests: `*Test.java` — run with `mvn test`
- Integration tests: `*IT.java` — run with `mvn verify`
- Test naming: `shouldDoSomethingWhenCondition()`
- Do NOT create integration tests that hit real external URLs

```bash
# Run a single test class
mvn test -pl sieve-core -Dtest=EntityTypeTest

# Run with coverage report
mvn verify  # JaCoCo reports at target/site/jacoco/index.html
```

## Logging

- Use SLF4J API everywhere
- Structured messages: `log.info("Action complete [key={}, count={}]", key, count)`
- No `System.out.println()` or `e.printStackTrace()`

## What NOT To Do

- Do NOT use Lombok
- Do NOT use MapStruct
- Do NOT add Spring Security (out of scope for Phase 1)
- Do NOT add a database or JPA
- Do NOT use `var` excessively — spell out types when it aids readability

## Pull Request Process

1. Fork the repository
2. Create a feature branch from `main`
3. Ensure all tests pass: `mvn clean verify`
4. Ensure code is formatted: `mvn spotless:check`
5. Submit a pull request with a clear description
