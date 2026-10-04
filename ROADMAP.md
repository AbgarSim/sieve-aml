# Sieve Roadmap

Sieve today screens names against 25 official sanctions lists. This roadmap grows it into a
**risk database with matching built in**: sanctions, politically exposed persons (PEPs) and their
relatives, crime, debarment and state-owned companies, linked by ownership and family relations,
screened on the whole profile, versioned and auditable.

The work runs in four phases, in this order. Each phase ends with a gate that must hold before the
next one starts. Each milestone is one pull request; its box is ticked when it merges.

| Phase | Focus | Gate |
| --- | --- | --- |
| 1 | Data coverage | Risk data is stored with its sources |
| 2 | Matching quality | Accuracy is measured in CI |
| 3 | Operations | Search, exports and API keys are live |
| 4 | Compliance | Any screening decision can be explained end to end |

## Phase 1: Data coverage

Turn the flat list of sanctions rows into a linked risk database, then fill it with new kinds of
risk. The data model comes first, because PEPs, relatives and ownership cannot be stored in today's
`SanctionedEntity` record.

### Foundations

- [x] 1.1 Stable ids: every source's ids carry a source prefix, so no list can overwrite another.
- [x] 1.2 Replace on refresh: a refresh swaps out that source's whole set, so delisted entries
  disappear and are recorded as removed.
- [x] 1.3 Risk entity model: entity kinds (person, company, organisation, vessel, aircraft, crypto
  wallet, security), risk topics (sanction, sanction-linked, PEP, relative or close associate,
  crime, wanted, debarment, export control, state-owned) and relations (owner, director, family,
  associate, position held). It follows an open, widely used entity schema so data can be imported
  and exported.
- [x] 1.4 Provenance on every value: each name, date and identifier records its source, source URL,
  first seen and last seen.
- [x] 1.5 Postgres as the system of record, with the in-memory screening index built from it.

### Sanctions, done fully

- [ ] 1.6 Keep fields providers already read: gender, deceased, listing reasons, vessel flag and
  tonnage.
- [x] 1.7 Crypto wallets from OFAC's digital currency addresses, as their own entity kind linked to
  the owner.
- [ ] 1.8 Relations already in the lists: OFAC "linked to", UN and EU associated entities, vessel
  owners and operators.
- [ ] 1.9 More sanctions and export-control lists: US BIS Entity List and Military End User list as
  their own sources, Singapore, Hong Kong, India, Kazakhstan, Argentina, and Ukraine with an API key.

### New risk types

- [ ] 1.10 Crime, wanted and debarment: World Bank and other development bank debarments, Interpol
  red notices, FBI and Europol most wanted, US SAM exclusions.
- [x] 1.11 PEPs from Wikidata: holders of national and regional positions (heads of state,
  ministers, members of parliament, senior judges, central bankers, military leaders), with start
  and end dates and a PEP tier per position.
- [x] 1.12 Relatives and close associates from Wikidata family and associate links, tied to their
  PEP.
- [ ] 1.13 National PEP lists: the lists of prominent public functions that EU member states publish
  under AMLD5, used to decide which positions count.
- [x] 1.14 Corporate ownership from GLEIF: LEI parent and child relations, which also give a first
  set of state-owned companies.
- [x] 1.15 Sanction-linked companies: companies majority-owned by a sanctioned party (the OFAC and
  EU 50% rules), derived from the ownership data held.
- [ ] 1.16 Adverse media, experimental: candidate articles from an open news index such as GDELT,
  kept separate from curated data and never treated as a match on their own.

**Gate:** every source has stable ids and drops delisted entries; PEP, crime, debarment and
ownership data are stored with provenance; the dashboard shows counts by risk topic.

## Phase 2: Matching quality

Make a hit mean something: screen on the whole profile, say why it matched, and measure accuracy on
a labelled test set.

- [x] 2.1 Fewer false positives: a lone first name no longer scores 1.0, and the name cache and
  n-gram index rebuild after every refresh.
- [x] 2.2 Name normalisation: accent folding, punctuation, transliteration from Cyrillic, Arabic,
  Chinese and other scripts, and company legal forms (LLC, OOO, GmbH) that no longer drive a match.
- [ ] 2.3 Screening request v2: entity kind, name, date or year of birth, nationality and country,
  identifiers, gender, address, and a filter by risk topic.
- [ ] 2.4 Feature scoring: the name score plus boosts and vetoes from date of birth, country,
  identifiers and gender, with configurable weights and a named algorithm version.
- [ ] 2.5 Score explanations: every result lists which features matched, how much each contributed,
  and which source values were compared.
- [ ] 2.6 Identifier matching: exact match on normalised passport, national id, IMO, LEI, SWIFT and
  wallet addresses, which counts as a strong match on its own.
- [ ] 2.7 Entity resolution at ingest: one person listed by several regimes becomes one entity with
  several sources, and manual merge or split decisions survive the next refresh.
- [ ] 2.8 Candidate search at scale: blocking and the n-gram index are benchmarked against a memory
  and latency budget for millions of names.
- [ ] 2.9 Evaluation set: labelled match and non-match pairs, with precision and recall checked in
  CI so a change that lowers either fails the build.

**Gate:** every result carries an explanation; precision and recall are measured; screening latency
stays inside budget on the full dataset.

## Phase 3: Operations

Run Sieve like a data product: every refresh is a version, changes are published as deltas, and the
database can be searched and exported, not only screened against.

- [ ] 3.1 Dataset versions: each ingest run is a numbered version, with the entities added, changed
  and removed since the last one.
- [ ] 3.2 Per-source schedules and health: each source refreshes on its own cadence, and a failed
  fetch or a sharp drop in entity count raises an alert instead of loading.
- [ ] 3.3 One server: both servers reach the same sources and features, then one is retired so
  behaviour cannot drift.
- [ ] 3.4 Data API: `/search` (free text and identifiers), `/entities/{id}` with relations and
  sources, `/match` v2, `/datasets` and `/changes`, in one OpenAPI spec.
- [ ] 3.5 Bulk exports: JSON lines, a flat CSV of targets, an entity-schema JSON file and daily
  delta files.
- [ ] 3.6 Access control: API keys, rate limits and per-key usage counts.
- [ ] 3.7 Live source checks: a scheduled job fetches every source so a change on a government site
  is caught early.

**Gate:** any entity can be looked up with its relations, full and delta exports can be downloaded,
each source's freshness is visible, and the API requires a key.

## Phase 4: Compliance

Give a compliance team what an auditor asks for: proof of what was screened against which data,
ongoing monitoring of customers, and a record of how every alert was handled.

- [ ] 4.1 Persistent audit trail: every screening stored with its request, results, algorithm
  version and dataset version, append-only and exportable.
- [ ] 4.2 Reproducible results: any past screening can be re-run against the dataset version it used.
- [ ] 4.3 Ongoing monitoring: customer portfolios are re-screened against each delta, and new or
  changed hits go to a webhook.
- [ ] 4.4 Alert handling: mark a hit as confirmed or false positive with a note, keep an allow list
  so cleared customers stop alerting, and optionally require a second reviewer.
- [ ] 4.5 Data quality report: per-source checks (missing names, bad dates, unresolved countries)
  published with each dataset version.
- [ ] 4.6 Personal data rules: PEP and relative data covers people who are not sanctioned, so it
  gets a stated legal basis, a retention policy, a removal-request process and access logging.
- [ ] 4.7 Source licences: each source's licence and attribution is recorded and shown on entity
  pages and exports.

**Done when:** a past decision can be explained end to end (request, data version, score
explanation, reviewer decision), and monitored customers are re-screened on every delta.
