# Sieve dashboard

Public dashboard for the nightly Sieve snapshot: totals per list, a world map of sanctioned entities by nationality and address, data quality, benchmarks, and a searchable list of every record with a full data card.

Built with Vite, React 18 and TypeScript; d3-geo draws the map from Natural Earth 110m geometry (`world-atlas`, bundled). The UI design comes from Claude Design.

## Data

The site is static. At runtime it reads the files written by `sieve snapshot` from `data/` next to `index.html`:

| File | Used by |
| --- | --- |
| `overview.json` | headline numbers, type mix, scripts, identifiers, largest programs |
| `sources.json` | sources table, completeness heatmap, ingest timeline |
| `countries.json` | world map, unresolved country values |
| `history.json` | sparklines and day-over-day changes (shown once there are two days) |
| `search-index.json` | search, loaded on first use |
| `entities/SOURCE/N.json` | entity data cards |

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

Routes: `/`, `/search?q=`, `/entity/SOURCE/id`, `/components`.

Sieve is a screening tool, not legal advice. Official lists are authoritative.
