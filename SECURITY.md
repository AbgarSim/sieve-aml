# Security policy

Sieve is open source under the MIT licence and has one maintainer, Abgar Simonean (@AbgarSim on GitHub), who works on it as a volunteer. This page says which versions are supported, how to report a vulnerability privately, what to put in the report, what happens after it, and which parts of the project the policy covers.

## Supported versions

There are no releases yet. The `main` branch is what is supported: CI builds and tests it on every push and pull request, and every fix lands there. The version in the poms, `0.1.0-SNAPSHOT`, is a build label, not a release.

| Version | Supported |
| --- | --- |
| `main` | Yes |
| An older checkout or any other branch | No. Update to `main` and check again before reporting. |

## How to report

Do not open a public issue that describes a vulnerability. Public issues are visible to everyone, and what is written in one cannot be made private afterwards.

1. Preferred: GitHub's private vulnerability reporting. Open the repository's Security tab and press "Report a vulnerability", or go straight to https://github.com/AbgarSim/sieve-aml/security/advisories/new. The report is visible only to the maintainer and to anyone the maintainer invites to it.
2. If that page says private reporting is not enabled for this repository, or the Security tab has no such button: open an issue at https://github.com/AbgarSim/sieve-aml/issues that says only that you have a security report, with no details of the finding. The maintainer will answer in the issue with a private channel for the details.

There is no reporting route outside GitHub.

## What to include

Write what you would want to read if you had to fix it:

- What you found and what it lets someone do: read data they should not, run code, crash the service, make the dashboard publish something it must not.
- Where it is: the module, class, endpoint or workflow file. For a dependency, name it and its version, the pom or `dashboard/package-lock.json` that pulls it in, and whether Sieve calls the affected code.
- The commit you tested, since there are no release versions: `git rev-parse HEAD` in your checkout.
- How to reproduce it: the request, input file or command, with the smallest input that shows the problem. A crafted list file in the publisher's format is more useful than a description of one.
- How you ran Sieve: which server or the CLI, and any flags, environment variables or `application.yml` settings that matter.
- A fix, if you have one. Hold the pull request back until the maintainer has read the report, because a pull request is public.

## What to expect

- One person reads the reports, in their free time. The maintainer acknowledges a report as soon as they see it and says what they plan to do. There are no response-time guarantees.
- The maintainer may ask for more detail through the private report, and will say when a fix is on `main`. Please keep the report private until then.
- A fix is a pull request to `main` like any other change, with an entry in [CHANGELOG.md](CHANGELOG.md) under `[Unreleased]`. There are no releases to backport to.
- Once the fix is on `main`, the report can be made public. A report that came in through GitHub's form is published as an advisory from the same place.
- If you want credit, say so and how you want to be named; the CHANGELOG entry for the fix will name you.

## Scope

Everything in this repository:

- The Java modules: `sieve-core`, `sieve-address`, `sieve-ingest`, `sieve-match`, `sieve-spring-server`, `sieve-server`, `sieve-cli` and `sieve-benchmark`. `sieve-ingest` parses XML, JSON, CSV, XLSX, HTML and PDF files downloaded from the publishers, and two of its providers drive a headless browser. A list file or response that makes a parser crash, hang, read or write files it should not, or run code, is in scope.
- The HTTP APIs of the two servers under `/api/v1`, served by `sieve-server` (Vert.x) and `sieve-spring-server` (Spring Boot), and the PostgreSQL access in the Spring server.
- The CLI (`sieve fetch`, `screen`, `stats`, `export`, `snapshot` and `media`), which runs with the rights of the user who starts it.
- The dashboard in `dashboard/`, as published at https://abgarsim.github.io/sieve-aml/. It is a static site that renders text taken from the lists. In scope are any way to run script from list content and any path by which the published data comes to hold records that must not be there (politically exposed persons and their associates are counted but never published).
- The GitHub Actions workflows in `.github/workflows/` (`ci.yml`, `dashboard.yml`, `benchmark.yml`): a way to run untrusted code with write permissions, to print a secret into a log, or to publish something other than the dashboard built from the checked-out code.
- A secret or key committed anywhere in the repository or its history.
- Dependencies with a known vulnerability, when Sieve uses the affected code.

## Out of scope

- The content of the lists themselves: entries that are wrong, missing, stale or badly parsed, and anything about the publishers' own sites. Those are data issues, so open a normal issue.
- Denial of service by sending huge batches, large bodies or many requests to a server you run yourself. The servers have no rate limiting, and that is known (see below).
- Findings that need physical access to the machine running Sieve, or that start from a shell or database login on it.
- The absence of authentication on the servers. It is known, described below, and item 3.6 of [ROADMAP.md](ROADMAP.md) plans API keys and rate limits.

## Running the servers

Both servers answer every request without authentication, over plain HTTP; nothing in them does TLS. Anyone who can reach the port can screen names, read every stored record through `GET /api/v1/lists/{source}/entities`, and start a refresh with `POST /api/v1/lists/refresh`, which downloads every enabled list from its publisher again. Run them where only the systems that need them can reach the port, and put a reverse proxy that does authentication and TLS in front when they must be reachable more widely.

What the servers do limit: the Vert.x server rejects request bodies above 1 MB and batches above 1000 names (`SIEVE_MAX_BATCH_SIZE` or `--max-batch-size`), and the Spring server rejects batches above 1000 names. Neither limits the rate of requests.

The Vert.x server logs every screening at INFO on the `dev.sieve.audit` logger (`dev.sieve.core.audit.LoggingAuditEmitter`), with the screened name, the outcome and the match count. Treat its logs as containing the names you screen. The Spring server logs screening requests only at DEBUG, which its default configuration does not print.

`docker-compose.yml` is a development setup: PostgreSQL runs with user `sieve` and password `sieve` and its port 5432 is published on the host, the Spring server listens on 8081 and the Vert.x server on 8080. Change `POSTGRES_PASSWORD` and stop publishing the database port before running it anywhere else.

A problem that goes beyond these known limits, in any of the areas above, is a security report; send it through the route at the top of this page.
