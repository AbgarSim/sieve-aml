# Contributing to Sieve

Sieve is open source under the MIT licence and has one maintainer, Abgar Simonean
(@AbgarSim on GitHub), who reviews and merges every change. This file says how to build and
test the project, what a change should look like, and how to send it. Everyone taking part
follows the [code of conduct](CODE_OF_CONDUCT.md). Security problems go through
[SECURITY.md](SECURITY.md), not through a public issue.

## Prerequisites

- Java 21. The build enforces Java 21 or later and compiles for release 21.
- Maven 3.9 or later (enforced too). The parent pom is not installed in your local
  repository, so a command that builds one module needs `-am` to build the parent and the
  modules it depends on.
- Node 22 and npm for the dashboard (CI uses Node 22 and `npm ci`).
- Python 3 and Doxygen for the documentation.
- Docker, optional, for a PostgreSQL to run the Spring Boot server or its database test.

## Build and test as CI does it

CI is `.github/workflows/ci.yml`. It runs on every push to `main` and on every pull request,
in two jobs:

- **Build and test**: Temurin 21 and a `postgres:16-alpine` service (database `sieve_test`,
  user `sieve`, password `sieve`), with `SIEVE_TEST_DB_URL=jdbc:postgresql://localhost:5432/sieve_test`,
  `SIEVE_TEST_DB_USER=sieve` and `SIEVE_TEST_DB_PASSWORD=sieve` in the environment, then

  ```bash
  mvn -B -ntp verify
  ```

- **Dashboard build**: Node 22, then in `dashboard/`, with `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`:

  ```bash
  npm ci
  npm run build
  ```

`mvn verify` compiles with `-Xlint:all`, checks the Java and Maven versions and bans duplicate
classes (maven-enforcer), runs the unit tests with Surefire, runs the `*IT.java` tests with
Failsafe, and writes a JaCoCo report to `<module>/target/site/jacoco/index.html`. It does not
run Spotless; see [Formatting](#formatting).

## Running one module or one test

```bash
# One module's tests, with the modules it depends on built first
mvn -q test -pl sieve-match -am

# One test class
mvn -q test -pl sieve-core -am -Dtest=EntityTypeTest -Dsurefire.failIfNoSpecifiedTests=false

# One module through verify: its unit tests and those of the modules it depends on,
# its Failsafe test and its JaCoCo report
mvn -q verify -pl sieve-spring-server -am
```

`-Dsurefire.failIfNoSpecifiedTests=false` keeps the build going in modules that have no test
of that name. Tests use JUnit 5 with AssertJ and Mockito and are named
`shouldDoSomethingWhenCondition`.

## Integration tests

Two kinds of test do not run by default.

**Live fetches from the publishers.** `sieve-ingest/src/test/java/dev/sieve/ingest/ProviderIntegrationTest.java`
is tagged `integration` and downloads every list from its publisher. The parent pom excludes
that tag (`<test.excludedGroups>integration</test.excludedGroups>`). To run it:

```bash
mvn -q test -pl sieve-ingest -am -Dgroups=integration -Dtest.excludedGroups=none
```

The IL and TR providers drive a browser through Playwright and need a Chromium; the workflows
install one with `java -cp "sieve-cli/target/lib/*" com.microsoft.playwright.CLI install --with-deps chromium`
after `mvn -B -ntp -q package -pl sieve-cli -am -DskipTests`. The UA NSDC test is disabled
until the provider has an API key (`SIEVE_NSDC_API_KEY`). Unit tests never reach a real URL:
each provider test parses a sample of the real file from `sieve-ingest/src/test/resources`.

**The PostgreSQL system of record.** `sieve-spring-server/src/test/java/dev/sieve/server/persistence/PersistentEntityIndexIT.java`
is the only Failsafe test. It is skipped unless `SIEVE_TEST_DB_URL` is set (`SIEVE_TEST_DB_USER`
and `SIEVE_TEST_DB_PASSWORD` default to `sieve`), and it cleans and migrates the database it
is given with Flyway, so point it at an empty, disposable database. CI provides one; locally
the `postgres` service in `docker-compose.yml` is one (database `sieve`, user `sieve`,
password `sieve`, port 5432):

```bash
docker compose up postgres
SIEVE_TEST_DB_URL=jdbc:postgresql://localhost:5432/sieve SIEVE_TEST_DB_USER=sieve SIEVE_TEST_DB_PASSWORD=sieve \
  mvn -q verify -pl sieve-spring-server -am
```

The other tests of the Spring Boot server are `@WebMvcTest` slices and need no database.

## Formatting

Java is formatted by Spotless 2.43.0 with google-java-format 1.24.0 in the AOSP style
(4-space indent), configured in the parent pom's `pluginManagement`. Nothing runs it for you,
CI included, so run it on the modules you changed before opening a pull request:

```bash
mvn -q spotless:check -pl sieve-core -am    # lists the files that need changes and fails if there are any
mvn -q spotless:apply -pl sieve-core -am    # rewrites them
```

If the check lists files your change did not touch, leave them for a pull request of their
own. `.editorconfig` at the root sets the rest: UTF-8, LF, a final newline, 4-space indent,
2 spaces for `yml`, `yaml`, `xml` and `json`, tabs in Makefiles.

## Repository layout

```
sieve-aml/
├── sieve-core/            # domain model, entity index, match SPI; depends on slf4j-api only
├── sieve-address/         # address parsing and normalisation through a libpostal JNI binding
├── sieve-ingest/          # list providers and parsers (Jackson, POI, PDFBox, Playwright)
├── sieve-match/           # matching engines, name normalisation and transliteration (ICU4J)
├── sieve-spring-server/   # Spring Boot REST API; PostgreSQL system of record (JDBC and Flyway)
├── sieve-server/          # Vert.x REST API, in memory only
├── sieve-cli/             # picocli CLI: fetch, screen, stats, export, snapshot, media
├── sieve-benchmark/       # JMH and real-data benchmarks, HTTP load test, matching evaluation
├── dashboard/             # the public dashboard (Vite, React, TypeScript) and the demo recorder
├── docs/                  # Sphinx documentation; the Doxyfile at the root feeds the Java API pages
├── .github/workflows/     # ci.yml, dashboard.yml (nightly site), benchmark.yml (on demand), adoption.yml (monthly)
├── Dockerfile             # Spring Boot server image, builds libpostal
├── Dockerfile.vertx       # Vert.x server image
├── docker-compose.yml     # postgres, sieve-spring and sieve-server services
├── CHANGELOG.md           # Keep a Changelog, with an [Unreleased] section
└── ROADMAP.md             # the milestones; each is one pull request
```

Dependencies between modules, as the poms declare them, point one way:

- `sieve-address`, `sieve-ingest` and `sieve-match` depend on `sieve-core` and not on each other.
- `sieve-server` and `sieve-spring-server` depend on `sieve-core`, `sieve-ingest`, `sieve-match`
  and `sieve-address`.
- `sieve-cli` and `sieve-benchmark` depend on `sieve-core`, `sieve-ingest` and `sieve-match`.

Keep it that way. A change that gives `sieve-core` a dependency, or makes `sieve-match` depend
on `sieve-ingest`, is a design change to raise in an issue first.

## Code conventions

- Records for value objects, with `Objects.requireNonNull` in constructors and compact record
  constructors.
- No `null` returned from a public method: `Optional` where absence is valid, otherwise an
  empty collection.
- Constructor injection. No `@Autowired` on fields in main code.
- Package-private by default; `public` only what other modules use.
- No raw types or unchecked casts without `@SuppressWarnings` and a comment saying why. The
  compiler runs with `-Xlint:all`.
- No Lombok, no MapStruct.
- Spell out a type where it helps the reader rather than reaching for `var`.
- Log through SLF4J with structured messages:
  `log.info("Action complete [key={}, count={}]", key, count)`. `System.out` and
  `printStackTrace` belong only in command-line entry points.
- Storage: only `sieve-spring-server` has a database. PostgreSQL is its system of record,
  reached through plain JDBC (no JPA), and schema changes are Flyway migrations under
  `sieve-spring-server/src/main/resources/db/migration/`. Every other module stays in memory.
- Tests: JUnit 5 with AssertJ and Mockito, fixtures under `src/test/resources`, no network
  except the `integration` tag above.

## Adding a data source

The source commits on `main` (for example `93ac52c`, India's banned organisations, and
`b31e258`, the FBI wanted list) all touch the same places. A pull request that adds a list:

1. Adds a value to `dev.sieve.core.model.ListSource` in `sieve-core`, with a Javadoc line and
   its display name, and updates `ListSourceTest` (the display-name row and the count in
   `shouldHaveExpectedValues`).
2. Adds a provider in a `dev.sieve.ingest.<code>` package of `sieve-ingest`, usually by
   extending `AbstractListProvider` and implementing `parseResponse(byte[])`; `source()`,
   `metadata()`, `fetch()` and `hasUpdates()` are final there. Entity ids carry a prefix of
   their own (`fbi-`, `in-mha-to-`), so no list can overwrite another's records.
3. Adds a sample of the real file to `sieve-ingest/src/test/resources` (`fbi_wanted_page1.json`,
   `in_mha_test_sample.html`) and a `<Provider>Test` next to the provider that parses it.
4. Adds a `<source>_shouldFetchAndParseEntities` method to `ProviderIntegrationTest`.
5. Registers the list in `dev.sieve.ingest.SourceCatalog` (publisher, jurisdiction, format,
   homepage; a new `SourceInfo.Format` if the file type is new) and the provider in
   `ProviderRegistry.defaults()`, which the CLI's `snapshot` command and the nightly dashboard use.
6. Adds the provider to the always-on list in `createProviders` of
   `sieve-server/src/main/java/dev/sieve/server/SieveServer.java`.
7. For a new parsing library: a version property and `dependencyManagement` entry in the
   parent pom and the dependency in `sieve-ingest/pom.xml`; `93ac52c` also had to exclude
   `commons-logging` from PDFBox in `sieve-spring-server/pom.xml`, so start the Spring Boot
   server once.
8. Updates the README: the row in the supported-lists tables and the counts in the text.
9. Adds a line under `[Unreleased]` in `CHANGELOG.md` naming the `ListSource` value and the
   id prefix.
10. Where the list brings a new jurisdiction code, names it in `dashboard/src/data/iso.ts` and
    places it in a region in `dashboard/src/data/snapshot.ts`, as `d6c3284` did for the World Bank.
11. Ticks the ROADMAP box when the list completes a milestone.

Two places do not change: the Spring Boot server wires only OFAC SDN, EU, UN and UK from
`application.yml` (`SieveConfiguration`), and the CLI's `CliContext` holds OFAC SDN alone;
only `sieve snapshot` reaches every provider. `docs/lists.rst` still describes the first four
lists and was not updated by the source commits; a pull request that brings it up to date is
welcome.

## Changing the dashboard

`dashboard/` is a Vite, React 18 and TypeScript site; `dashboard/README.md` describes the data
files it reads and its routes. Playwright is pinned to 1.63.0 for the demo recorder. The
scripts in `package.json` are `dev`, `build` (`tsc -b && vite build`), `preview`, `typecheck`
(`tsc -b`) and `demo` (`node demo/record.mjs`); there is no lint script.

```bash
cd dashboard
npm ci
npm run build       # what CI runs on every pull request
npm run dev         # http://localhost:5173
```

In `dev` and `preview` the `/data/` files come from `$SIEVE_DATA_DIR`, else from `../snapshot`,
which `java -jar sieve-cli/target/sieve-cli-0.1.0-SNAPSHOT.jar snapshot --out snapshot` writes,
else from the fictional `dashboard/sample/`, which the UI marks as sample data. `npm run demo`
records the walkthrough with `dashboard/demo/record.mjs` and needs `ffmpeg` and a Chromium from
`npx playwright install chromium`. The benchmark figures shown on the site are kept in
`dashboard/src/data/benchmarks.ts` and must match `docs/performance/benchmarks.rst`.

`.github/workflows/dashboard.yml` builds the site from `main` every night at 03:00 UTC and on
`workflow_dispatch`, and publishes it to GitHub Pages; a dispatch with the `sources` input set
builds and records but does not deploy.

## Changing the docs

`docs/` is a Sphinx site (furo theme, myst-parser, breathe, sphinxcontrib-mermaid,
sphinx-copybutton, sphinx-design) with the Java API pages generated by Doxygen: the `Doxyfile`
at the root writes XML to `doxygen/xml`, which breathe reads. The pages hang off the toctree in
`docs/index.rst` (getting-started, api, cli, algorithms, architecture, performance, java-api),
with `docs/lists.rst` and other pages beside it. The site is not published anywhere yet, so
build it locally to check a change:

```bash
pip install -r docs/requirements.txt
make -C docs html                 # runs doxygen Doxyfile first; output in docs/_build/html
make -C docs html-no-doxygen      # Sphinx only
make -C docs linkcheck
```

The README is what most people read first; when a change alters a command, a flag, a list or
a number there, change the README and the docs page together.

## Pull requests

- Branch from `main` (fork the repository first if you are not the maintainer). One change per
  pull request; a ROADMAP milestone is one pull request.
- Commit subjects are one plain imperative sentence saying what the change does for the
  reader, for example "Add India's banned organisations under UAPA" or "Screen on worker
  threads in the Vert.x server instead of the event loop".
- The description opens with a "Before:" paragraph and an "After:" paragraph, then a short
  "How" paragraph. `.github/pull_request_template.md` has the headings and the checklist.
- Add tests for the change and a line under `[Unreleased]` in `CHANGELOG.md`
  ([Keep a Changelog](https://keepachangelog.com)), and tick the ROADMAP box if the pull
  request completes a milestone.
- Run Spotless on the modules you touched. Both CI jobs must pass.
- Sign every commit off (next section).
- The maintainer reviews and merges. There is no other reviewer, so a reply can take a while.

## Developer Certificate of Origin

Every pull request from anyone other than the maintainer must have every commit signed off
under the [Developer Certificate of Origin](https://developercertificate.org), version 1.1.
Signing off certifies that you wrote the change or otherwise have the right to submit it under
the MIT licence, and that you understand the contribution is public and will be kept with your
sign-off. Add it with

```bash
git commit -s
```

which appends a `Signed-off-by` line with the name and address from your git configuration.
A pull request with unsigned commits is sent back for them to be amended.

## Where to ask

Open an [issue](https://github.com/AbgarSim/sieve-aml/issues) for a bug, a feature, a new list
or a question; the forms under `.github/ISSUE_TEMPLATE/` ask for what the maintainer needs.
Issues labelled [good first issue](https://github.com/AbgarSim/sieve-aml/labels/good%20first%20issue)
are small and self-contained; [help wanted](https://github.com/AbgarSim/sieve-aml/labels/help%20wanted)
marks work the maintainer would like someone to take. Security reports do not go in issues:
follow [SECURITY.md](SECURITY.md). Conduct concerns follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
