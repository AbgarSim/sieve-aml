// Loads the nightly snapshot written by `sieve snapshot` and adapts it to the shapes the UI renders.
import { EU_MEMBERS, ISO_NUMERIC, countryName } from './iso';
import type { RawCountries, RawHistoryRow, RawOverview, RawProgramCount, RawSource, RawSources, RawStatus, RawType } from './raw';

export type EntityType = 'individual' | 'entity' | 'vessel' | 'aircraft';
export const TYPES: EntityType[] = ['individual', 'entity', 'vessel', 'aircraft'];
export const TYPE_LABEL: Record<EntityType, string> = { individual: 'Individual', entity: 'Entity', vessel: 'Vessel', aircraft: 'Aircraft' };
export const RAW_TYPE: Record<RawType, EntityType> = { INDIVIDUAL: 'individual', ENTITY: 'entity', VESSEL: 'vessel', AIRCRAFT: 'aircraft' };

export type Status = 'loaded' | 'empty' | 'failed' | 'needs-key' | 'skipped';
const STATUS: Record<RawStatus, Status> = { LOADED: 'loaded', EMPTY: 'empty', FAILED: 'failed', NEEDS_KEY: 'needs-key', SKIPPED: 'skipped' };

/** Completeness columns, in the order of {@link Source.completeness}. */
export const FIELDS = ['Date of birth', 'Nationality', 'Address', 'Identifiers', 'Aliases', 'Program', 'Listing date'];

export interface Program { source: string; code: string; name: string; entities: number }

export interface Source {
  id: string; name: string; cc: string; authority: string; region: string; format: string; homepage: string; listUri?: string;
  entities: number; names: number; countries: number; status: Status; error?: string; fetchMs: number | null; lastFetched: string | null;
  /** Percent of entities with each of {@link FIELDS}. */
  completeness: number[];
  /** Share of individuals, entities, vessels and aircraft. */
  types: number[];
  topPrograms: Program[];
  /** Entity counts over the last 30 snapshots, when history has at least two days. */
  spark: number[] | null;
  delta: number | null;
  changedDays: number | null;
}

export interface Country {
  cc: string; num: string; name: string;
  /** Entities linked by nationality or address. */
  total: number; nationality: number; address: number;
  bySource: Record<string, number>; byType: Record<EntityType, number>;
}

export interface Snapshot {
  sources: Source[]; byId: Record<string, Source>;
  totalEntities: number; totalNames: number; byType: Record<EntityType, number>;
  countries: Country[]; countryByNum: Record<string, Country>; countryByCc: Record<string, Country>;
  /** Source ids by the country whose authority publishes them; EU lists count for every member state. */
  authorities: Record<string, string[]>;
  /** Issuer country, target country, entities. */
  flows: [string, string, number][];
  unresolved: { occurrences: number; top: [string, number][] };
  programs: Program[]; distinctPrograms: number;
  namesByScript: [string, number][]; identifiersByType: [string, number][];
  history: RawHistoryRow[];
  snapshot: { generatedAt: string; date: string; time: string; commit: string | null; sourcesLoaded: number; sourcesTotal: number; countries: number; ingestMs: number | null; sample: boolean };
}

/** The loaded snapshot. Assigned by {@link loadSnapshot} before the app renders. */
export let SIEVE: Snapshot;

export const DATA_URL = (import.meta.env?.VITE_DATA_URL as string | undefined) ?? 'data/';

export async function fetchJson<T>(path: string): Promise<T> {
  const r = await fetch(DATA_URL + path);
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return r.json() as Promise<T>;
}

export async function loadSnapshot(): Promise<Snapshot> {
  const [overview, sources, countries, history] = await Promise.all([
    fetchJson<RawOverview>('overview.json'),
    fetchJson<RawSources>('sources.json'),
    fetchJson<RawCountries>('countries.json'),
    fetchJson<RawHistoryRow[]>('history.json').catch(() => [] as RawHistoryRow[]),
  ]);
  SIEVE = adapt(overview, sources, countries, history);
  return SIEVE;
}

const REGION: Record<string, string> = {
  US: 'North America', CA: 'North America', UN: 'International',
  AU: 'Asia-Pacific', NZ: 'Asia-Pacific', JP: 'Asia-Pacific',
  TR: 'Middle East & Africa', IL: 'Middle East & Africa', QA: 'Middle East & Africa', ZA: 'Middle East & Africa',
};
/** Where flows from EU lists are drawn from on the map. */
const EU_SEAT = 'BE';

const typeCounts = (m?: Partial<Record<RawType, number>>): Record<EntityType, number> =>
  ({ individual: m?.INDIVIDUAL ?? 0, entity: m?.ENTITY ?? 0, vessel: m?.VESSEL ?? 0, aircraft: m?.AIRCRAFT ?? 0 });
const program = (p: RawProgramCount): Program => ({ source: p.source, code: p.code, name: p.name ?? '', entities: p.entities });
const sorted = (m?: Record<string, number>) => Object.entries(m ?? {}).sort((a, b) => b[1] - a[1]);
const days = (a: string, b: string) => Math.round((Date.parse(b) - Date.parse(a)) / 86_400_000);

function adapt(o: RawOverview, s: RawSources, c: RawCountries, history: RawHistoryRow[]): Snapshot {
  const hist = [...history].sort((a, b) => a.date.localeCompare(b.date));
  const recent = hist.slice(-30);
  const hasHistory = recent.length >= 2;

  const sources: Source[] = s.sources.map(r => source(r, recent, hasHistory));
  const byId = Object.fromEntries(sources.map(x => [x.id, x]));

  const countries: Country[] = Object.entries(c.countries ?? {}).map(([cc, r]) => ({
    cc, num: ISO_NUMERIC[cc] ?? cc, name: r.name || countryName(cc),
    total: r.entities, nationality: r.byNationality ?? 0, address: r.byAddress ?? 0,
    bySource: r.bySource ?? {}, byType: typeCounts(r.byType),
  }));

  const authorities: Record<string, string[]> = {};
  const add = (cc: string, id: string) => (authorities[cc] ??= []).push(id);
  sources.forEach(x => { const j = x.cc.toUpperCase(); if (j === 'EU') EU_MEMBERS.forEach(m => add(m, x.id)); else if (j !== 'UN') add(j, x.id); });

  const flows: [string, string, number][] = [];
  sources.forEach(x => {
    const j = x.cc.toUpperCase(), from = j === 'EU' ? EU_SEAT : j;
    if (j === 'UN') return;
    countries.forEach(ct => { const n = ct.bySource[x.id]; if (n && ct.cc !== from) flows.push([from, ct.cc, n]); });
  });
  // Merge lists from the same issuer, then keep the strongest links so the map stays readable
  const merged = new Map<string, [string, string, number]>();
  flows.forEach(f => { const k = f[0] + f[1], m = merged.get(k); if (m) m[2] += f[2]; else merged.set(k, [...f]); });

  const at = new Date(o.generatedAt);
  return {
    sources, byId,
    totalEntities: o.totalEntities, totalNames: o.totalNames, byType: typeCounts(o.byType),
    countries, countryByNum: Object.fromEntries(countries.map(x => [x.num, x])), countryByCc: Object.fromEntries(countries.map(x => [x.cc, x])),
    authorities, flows: [...merged.values()].sort((a, b) => b[2] - a[2]).slice(0, 40),
    unresolved: { occurrences: c.unresolved?.occurrences ?? 0, top: sorted(c.unresolved?.topValues) },
    programs: (o.topPrograms ?? []).map(program), distinctPrograms: o.distinctPrograms,
    namesByScript: sorted(o.namesByScript), identifiersByType: sorted(o.identifiersByType),
    history: hist,
    snapshot: {
      generatedAt: o.generatedAt, date: at.toISOString().slice(0, 10), time: at.toISOString().slice(11, 16) + ' UTC',
      commit: o.commit ? o.commit.slice(0, 7) : null, sourcesLoaded: o.sourcesLoaded, sourcesTotal: o.sourcesTotal,
      countries: o.countries, ingestMs: o.ingest?.longestMs ?? null, sample: !!o.sample,
    },
  };
}

function source(r: RawSource, recent: RawHistoryRow[], hasHistory: boolean): Source {
  const k = r.completeness, pct = (n: number) => (k && k.total ? Math.round((n / k.total) * 100) : 0);
  const t = typeCounts(r.byType), tsum = TYPES.reduce((a, x) => a + t[x], 0) || 1;
  const series = recent.map(h => h.bySource?.[r.source] ?? 0);
  let changedDays: number | null = null;
  if (hasHistory) {
    const last = series[series.length - 1];
    for (let i = series.length - 2; i >= 0; i--) if (series[i] !== last) { changedDays = days(recent[i + 1].date, recent[recent.length - 1].date); break; }
  }
  return {
    id: r.source, name: r.displayName, cc: r.jurisdiction.toLowerCase(), authority: r.authority,
    region: REGION[r.jurisdiction] ?? 'Europe', format: r.format, homepage: r.homepage, listUri: r.listUri,
    entities: r.entities, names: r.names ?? 0, countries: r.countries ?? 0, status: STATUS[r.status] ?? 'skipped', error: r.error,
    fetchMs: r.status === 'NEEDS_KEY' || r.status === 'SKIPPED' ? null : r.fetchMs ?? null, lastFetched: r.lastFetched ?? null,
    completeness: k ? [k.withDateOfBirth, k.withNationality, k.withAddress, k.withIdentifiers, k.withAliases, k.withProgram, k.withListedDate].map(pct) : [0, 0, 0, 0, 0, 0, 0],
    types: TYPES.map(x => t[x] / tsum),
    topPrograms: (r.topPrograms ?? []).map(program),
    spark: hasHistory ? series : null,
    delta: hasHistory ? series[series.length - 1] - series[series.length - 2] : null,
    changedDays,
  };
}
