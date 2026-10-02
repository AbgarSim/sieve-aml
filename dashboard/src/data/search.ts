// Client-side screening over the snapshot's search index, and entity records from the shard files.
import { fetchJson, RAW_TYPE, type EntityType } from './snapshot';
import type { RawEntity, RawIndex, RawIndexEntry } from './raw';
import { jaroWinkler as jw, normalize as norm, tokens } from '../lib/jw';

const T: Record<RawIndexEntry['t'], EntityType> = { I: 'individual', E: 'entity', V: 'vessel', A: 'aircraft' };

export interface Entry { key: string; source: string; id: string; name: string; aliases: string[]; type: EntityType; countries: string[]; programs: string[]; shard: number }

export interface Index {
  entries: Entry[];
  byKey: Map<string, Entry>;
  /** Normalised names of each entry, primary name first. */
  names: string[][];
  /** Entries by normalised name token. */
  vocab: Map<string, number[]>;
  /** Entries by normalised primary name, to find the same record on other lists. */
  byName: Map<string, number[]>;
}

let pending: Promise<Index> | null = null;

/** Loads the search index once; later calls share the same promise. */
export function loadIndex(): Promise<Index> {
  pending ??= fetchJson<RawIndex>('search-index.json').then(build).catch(e => { pending = null; throw e; });
  return pending;
}

export function build(raw: RawIndex): Index {
  const entries: Entry[] = (raw.entries ?? []).map(e => {
    const slash = e.k.indexOf('/');
    return { key: e.k, source: e.s, id: e.k.slice(slash + 1), name: e.n, aliases: e.a ?? [], type: T[e.t], countries: e.c ?? [], programs: e.p ?? [], shard: e.f };
  });
  const names: string[][] = [];
  const vocab = new Map<string, number[]>();
  const byName = new Map<string, number[]>();
  entries.forEach((e, i) => {
    const ns = [e.name, ...e.aliases].map(norm);
    names.push(ns);
    const seen = new Set<string>();
    ns.forEach(n => tokens(n).forEach(t => { if (!seen.has(t)) { seen.add(t); let l = vocab.get(t); if (!l) vocab.set(t, (l = [])); l.push(i); } }));
    let l = byName.get(ns[0]); if (!l) byName.set(ns[0], (l = [])); l.push(i);
  });
  return { entries, byKey: new Map(entries.map(e => [e.key, e])), names, vocab, byName };
}

export interface Hit { e: Entry; s: number; name: string }

/**
 * Scores entries against a query with Jaro-Winkler, as sieve-match does: the whole name, each
 * token, and substring containment. Only entries sharing a similar token are scored.
 */
export function search(ix: Index, q: string, threshold: number): Hit[] {
  const qn = norm(q); if (!qn) return [];
  const qt = tokens(qn);
  const floor = Math.min(threshold, 0.8);
  const candidates = new Set<number>();
  for (const t of qt) {
    for (const [tok, ids] of ix.vocab) {
      if ((t.length >= 3 && tok.startsWith(t)) || (Math.abs(tok.length - t.length) <= 3 && jw(t, tok) >= floor)) ids.forEach(i => candidates.add(i));
    }
  }
  const hits: Hit[] = [];
  candidates.forEach(i => {
    const e = ix.entries[i], all = [e.name, ...e.aliases];
    let best = { s: 0, name: e.name };
    ix.names[i].forEach((nn, j) => {
      let s = jw(qn, nn);
      if (nn.includes(qn)) s = Math.max(s, 0.92 + Math.min(0.08, (qn.length / nn.length) * 0.08));
      const words = tokens(nn);
      let tok = 0; qt.forEach(t => { tok += Math.max(0, ...words.map(w => jw(t, w))); }); tok /= qt.length;
      s = Math.max(s, tok * 0.98);
      if (s > best.s) best = { s, name: all[j] };
    });
    if (best.s >= threshold) hits.push({ e, ...best });
  });
  return hits.sort((a, b) => b.s - a.s);
}

/** Other lists carrying a record with the same normalised primary name and type. */
export function sameName(ix: Index, e: Entry): Entry[] {
  return (ix.byName.get(norm(e.name)) ?? []).map(i => ix.entries[i]).filter(o => o.key !== e.key && o.type === e.type);
}

const shards = new Map<string, Promise<RawEntity[]>>();

/** Loads the full record of an index entry from its shard file. */
export async function loadEntity(e: Entry): Promise<RawEntity | undefined> {
  const path = `entities/${e.source}/${e.shard}.json`;
  let p = shards.get(path);
  if (!p) { p = fetchJson<RawEntity[]>(path); shards.set(path, p); p.catch(() => shards.delete(path)); }
  return (await p).find(r => r.id === e.id);
}

export const entityType = (r: RawEntity) => RAW_TYPE[r.entityType];
