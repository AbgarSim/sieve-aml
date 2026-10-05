# Sieve dashboard

Public dashboard for the nightly Sieve snapshot: totals per list, a world map of sanctioned entities by nationality and address, data quality, benchmarks, and a searchable list of every record with a full data card.

Built with Vite, React 18 and TypeScript; d3-geo draws the map from Natural Earth 110m geometry (`world-atlas`, bundled). The UI design comes from Claude Design.

## Data

The site is static. At runtime it reads the files written by `sieve snapshot` from `data/` next to `index.html`:

| File | Used by |
| --- | --- |
| `overview.json` | headline numbers, type mix, risk topics, scripts, identifiers, largest programs |
| `sources.json` | sources table, source pages (with each list's description), completeness heatmap, ingest timeline |
| `countries.json` | world map, unresolved country values |
| `history.json` | sparklines and day-over-day changes (shown once there are two days) |
| `search-index.json` | search, loaded on first use |
| `entities/SOURCE/N.json` | entity profiles |
| `relations.json` | association graph on the entity page, loaded on first use |
| `seen.json` | not read by the UI: when each record was first seen and last changed, carried from night to night |


One entity listed by several authorities is matched across lists when the snapshot is written (by name, identifiers and date of birth); the records of such an entity share a group id (`g` in `search-index.json`), which the search folds into one result and the entity page lists as the same entity on other lists. `overview.json` counts the distinct entities in `dedup`, and each list's row in `sources.json` says in `onOtherLists` how many of its records another list also carries.

The entity page is one profile of the entity across every list that carries it: a header with its type, risk topics (sanctioned, export controlled, debarred, wanted and so on) and a one-line summary; a merged property table whose values cite the lists that state them; every listing with its program and dates; the relations the lists state, both ways; and one section per list with that list's description, its own record, reasons for listing and remarks, and the record's history. Entity records carry `topics`, `relations` (with `targetKey` when the target is a published record), `linkedFrom`, and `firstSeen`, `lastSeen` and `lastChange`; index entries carry topics in `o` unless the record is only sanctioned.

`relations.json` holds the links the lists state between published records (`f` holds the link to `t`, `r` is the kind, `l` the list's wording, `p` an ownership share, `s` and `e` the dates) and the country each linked record is placed at on the graph (`home`). The entity page folds them onto entities the same way the search does.

Politically exposed persons and their associates are counted (`byTopic` in `overview.json`, and per list in `sources.json`, whose `published` field says how many of a list's records were written) but never appear in `entities/` or the search index, so the UI shows their numbers and marks those lists as counts only.

Benchmark figures are the published results in `docs/performance/benchmarks.rst`, kept in `src/data/benchmarks.ts`.

## Develop

```bash
cd dashboard
npm install
npm run dev        # http://localhost:5173
npm run build      # static site in dist/; copy a snapshot into dist/data/ to publish
```

In dev and preview, `/data/` is served from `$SIEVE_DATA_DIR`, else `../snapshot` (the default output of `sieve snapshot --out snapshot`), else `sample/`. The sample is fictional data in the real file format, and the UI marks it as sample data.

## Publish

`.github/workflows/dashboard.yml` runs nightly at 03:00 UTC: it fetches every list, writes the snapshot, builds this site and deploys both to GitHub Pages. It needs **Settings → Pages → Source: GitHub Actions**.

Routes: `/`, `/search?q=&source=&topic=&country=`, `/entity/SOURCE/id`, `/source/SOURCE`, `/components`.

Sieve is a screening tool, not legal advice. Official lists are authoritative.
