// Loads the nightly snapshot written by `sieve snapshot` and adapts it to the shapes the UI renders.
import { EU_MEMBERS, ISO_NUMERIC, countryName } from './iso';
import type { RawCountries, RawHistoryRow, RawOverview, RawProgramCount, RawSource, RawSources, RawStatus, RawType } from './raw';

export type EntityType = 'individual' | 'entity' | 'company' | 'organization' | 'vessel' | 'aircraft' | 'wallet' | 'security';
export const TYPES: EntityType[] = ['individual', 'entity', 'company', 'organization', 'vessel', 'aircraft', 'wallet', 'security'];
export const TYPE_LABEL: Record<EntityType, string> = {
  individual: 'Individual', entity: 'Entity', company: 'Company', organization: 'Organisation',
  vessel: 'Vessel', aircraft: 'Aircraft', wallet: 'Crypto wallet', security: 'Security',
};
export const RAW_TYPE: Record<RawType, EntityType> = {
  INDIVIDUAL: 'individual', ENTITY: 'entity', COMPANY: 'company', ORGANIZATION: 'organization',
  VESSEL: 'vessel', AIRCRAFT: 'aircraft', CRYPTO_WALLET: 'wallet', SECURITY: 'security',
};

export type Status = 'loaded' | 'empty' | 'failed' | 'needs-key' | 'skipped';
const STATUS: Record<RawStatus, Status> = { LOADED: 'loaded', EMPTY: 'empty', FAILED: 'failed', NEEDS_KEY: 'needs-key', SKIPPED: 'skipped' };

/** Risk topics in the order badges show them, most severe first. */
export const TOPICS = ['SANCTION', 'SANCTION_LINKED', 'WANTED', 'CRIME', 'EXPORT_CONTROL', 'DEBARMENT', 'PEP', 'RCA', 'STATE_OWNED'];
export const TOPIC_LABEL: Record<string, string> = {
  SANCTION: 'Sanctioned', SANCTION_LINKED: 'Sanction-linked', EXPORT_CONTROL: 'Export controlled', DEBARMENT: 'Debarred',
  PEP: 'Politically exposed', RCA: 'Relative or associate', CRIME: 'Crime', WANTED: 'Wanted', STATE_OWNED: 'State-owned',
};
/** What each topic means, for badge tooltips. */
export const TOPIC_HINT: Record<string, string> = {
  SANCTION: 'Listed under a sanctions program: asset freeze, travel ban or arms embargo',
  SANCTION_LINKED: 'Owned or controlled by a sanctioned party without being listed itself',
  EXPORT_CONTROL: 'Subject to export restrictions, such as an entity or end-user list',
  DEBARMENT: 'Excluded from contracts by a government or development bank',
  PEP: 'Holds or has held a prominent public function',
  RCA: 'A relative or close associate of a politically exposed person',
  CRIME: 'Convicted of, or charged with, a crime',
  WANTED: 'Sought by law enforcement',
  STATE_OWNED: 'Owned or controlled by a state',
};
export const byTopicOrder = (a: string, b: string) => (TOPICS.indexOf(a) + 1 || 99) - (TOPICS.indexOf(b) + 1 || 99);
export const topicLabel = (k: string) => TOPIC_LABEL[k] ?? k.charAt(0) + k.slice(1).toLowerCase().replace(/_/g, ' ');
/** What a count of entities with this topic is a count of, for prose. */
export const topicNoun = (k: string) => ({ PEP: 'politically exposed persons', RCA: 'relatives and close associates' })[k] ?? topicLabel(k).toLowerCase() + ' entities';

/** Completeness columns, in the order of {@link Source.completeness}. */
export const FIELDS = ['Date of birth', 'Nationality', 'Address', 'Identifiers', 'Aliases', 'Program', 'Listing date'];

export interface Program { source: string; code: string; name: string; entities: number }

export interface Source {
  id: string; name: string; cc: string; authority: string; region: string; format: string; homepage: string; listUri?: string;
  /** What the list is and who is on it; empty in snapshots written before descriptions. */
  description: string;
  entities: number; names: number; countries: number; status: Status; error?: string; fetchMs: number | null; lastFetched: string | null;
  /** Entities whose records are published; the rest are counted only. */
  published: number;
  /** Published records that another list also carries. */
  onOtherLists: number;
  /** True for a list whose records are all counted but never published, such as politically exposed persons. */
  countsOnly: boolean;
  topics: [string, number][];
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
  /** Entities per risk topic across every list, counted-only records included. */
  byTopic: [string, number][];
  /** Entities counted in the lists but not published as records: politically exposed persons and their associates. */
  unpublished: number;
  /** Published records after matching one entity's records across lists, when the snapshot did that. */
  distinctEntities: number | null;
  /** Distinct entities found on more than one list. */
  onSeveralLists: number | null;
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
  US: 'North America', CA: 'North America', UN: 'International', WB: 'International', WD: 'International', LE: 'International',
  AU: 'Asia-Pacific', NZ: 'Asia-Pacific', JP: 'Asia-Pacific',
  TR: 'Middle East & Africa', IL: 'Middle East & Africa', QA: 'Middle East & Africa', ZA: 'Middle East & Africa',
};
/** Where flows from EU lists are drawn from on the map. */
const EU_SEAT = 'BE';

const typeCounts = (m?: Partial<Record<RawType, number>>): Record<EntityType, number> => {
  const counts = Object.fromEntries(TYPES.map(t => [t, 0])) as Record<EntityType, number>;
  for (const [raw, n] of Object.entries(m ?? {})) if (raw in RAW_TYPE) counts[RAW_TYPE[raw as RawType]] += n ?? 0;
  return counts;
};
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
    byTopic: sorted(o.byTopic), unpublished: sources.reduce((a, x) => a + (x.entities - x.published), 0),
    distinctEntities: o.dedup?.distinctEntities ?? null, onSeveralLists: o.dedup?.onSeveralLists ?? null,
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
    region: REGION[r.jurisdiction] ?? 'Europe', format: r.format, homepage: r.homepage, listUri: r.listUri, description: r.description ?? '',
    entities: r.entities, names: r.names ?? 0, countries: r.countries ?? 0, status: STATUS[r.status] ?? 'skipped', error: r.error,
    published: r.published ?? r.entities, onOtherLists: r.onOtherLists ?? 0, countsOnly: r.entities > 0 && (r.published ?? r.entities) === 0, topics: sorted(r.byTopic),
    fetchMs: r.status === 'NEEDS_KEY' || r.status === 'SKIPPED' ? null : r.fetchMs ?? null, lastFetched: r.lastFetched ?? null,
    completeness: k ? [k.withDateOfBirth, k.withNationality, k.withAddress, k.withIdentifiers, k.withAliases, k.withProgram, k.withListedDate].map(pct) : [0, 0, 0, 0, 0, 0, 0],
    types: TYPES.map(x => t[x] / tsum),
    topPrograms: (r.topPrograms ?? []).map(program),
    spark: hasHistory ? series : null,
    delta: hasHistory ? series[series.length - 1] - series[series.length - 2] : null,
    changedDays,
  };
}
