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

## Demo

`demo/record.mjs` records the demo from a built site, so that the demo is the application as built that night. It picks from `search-index.json` the person on OFAC SDN who is on the most other lists (the person on the most lists when none is on OFAC SDN; ties go to the most relations), drives the site in a headless Chromium with a drawn pointer and captions (overview, search typed letter by letter, the profile, the association graph, a list's page, the light theme) and records a video (`walkthrough.webm` and `.mp4`), a GIF of the screening steps encoded coarser, then cut shorter, to stay under 4 MB (the Demo page omits the GIF link when it still cannot), and a screenshot per step. With `--api` it waits for a Vert.x server to report a loaded index (the workflow first waits for every list to be in, so start the server and let it finish loading before recording locally) and records a health call, a screening of the name as listed, a misspelling and a batch; with `--cli` it runs `sieve screen` (which fetches OFAC SDN first). Everything, with timings, goes into `demo/manifest.json`, which the Demo page (`/demo`) reads; the files are listed in it so that the workflow can carry a demo over when a night's recording fails.

```bash
npm run build && npm run preview                       # in one terminal: the site on a snapshot, port 4173
npm run demo -- --site http://localhost:4173/ --api none --cli none   # in another: into dist/demo, about a minute
npm run demo -- --api http://localhost:8080 --cli ../sieve-cli/target/sieve-cli-0.1.0-SNAPSHOT.jar
```

It needs `ffmpeg` and the Chromium that `npx playwright install chromium` fetches (`PLAYWRIGHT_CHROMIUM_PATH` points it at another build).

## Publish

`.github/workflows/dashboard.yml` runs nightly at 03:00 UTC: it fetches every list, writes the snapshot, builds this site with last night's demo carried over from the published site, records tonight's demo from the built site and the server once every list is loaded, and deploys everything to GitHub Pages. A failed recording keeps last night's demo. It needs **Settings → Pages → Source: GitHub Actions**. A run with the `sources` input set builds and records but does not deploy.

Routes: `/`, `/search?q=&source=&topic=&country=`, `/entity/SOURCE/id`, `/source/SOURCE`, `/demo`, `/components`.

Sieve is a screening tool, not legal advice. Official lists are authoritative.
