// Links between entities from relations.json (see RelationGraph in sieve-cli), folded onto the
// entities the search index groups across lists, so one person listed by three authorities is one node.
import { fetchJson } from './snapshot';
import type { Index } from './search';

/** Relation kinds, after the interval schemata of the Follow the Money model. */
export type RelType = 'OWNERSHIP' | 'DIRECTORSHIP' | 'FAMILY' | 'ASSOCIATE' | 'LINKED';
export const REL_TYPES: RelType[] = ['OWNERSHIP', 'DIRECTORSHIP', 'FAMILY', 'ASSOCIATE', 'LINKED'];
export const REL_LABEL: Record<RelType, string> = {
  OWNERSHIP: 'Ownership', DIRECTORSHIP: 'Directorship', FAMILY: 'Family', ASSOCIATE: 'Associate', LINKED: 'Other link',
};
/** Kinds with a direction: the holder owns, or directs, the target. */
export const DIRECTED: ReadonlySet<RelType> = new Set<RelType>(['OWNERSHIP', 'DIRECTORSHIP']);

export interface RawEdge { f: string; t: string; r: string; l?: string; p?: number; s?: string; e?: string }
export interface RawRelations {
  formatVersion: number; generatedAt: string;
  stats?: { edges: number; entities: number; byType?: Record<string, number>; dropped?: Record<string, number> };
  edges?: RawEdge[];
  /** Country each linked record is placed at: nationality, else address, else flag. */
  home?: Record<string, string>;
}

/** One link between two entities, from a to b for the directed kinds. */
export interface Link {
  id: string; a: string; b: string; type: RelType;
  /** The link as the lists word it, such as "brother of" or "owned by". */
  roles: string[];
  share?: number; start?: string; end?: string;
  /** Lists that state the link. */
  lists: string[];
}

export interface Graph {
  /** Links of each entity, by group key. */
  links: Map<string, Link[]>;
  /** Country each linked entity is placed at, by group key. */
  home: Map<string, string>;
}

const kind = (r: string): RelType => ((REL_TYPES as string[]).includes(r) ? (r as RelType) : 'LINKED');

export function buildGraph(raw: RawRelations, ix: Index): Graph {
  const group = (k: string) => ix.byKey.get(k)?.group ?? k;
  const byPair = new Map<string, Map<string, Link>>();
  const home = new Map<string, string>();
  const rawHome = raw.home ?? {};
  for (const e of raw.edges ?? []) {
    const a = group(e.f), b = group(e.t);
    if (a === b) continue;
    for (const [k, g] of [[e.f, a], [e.t, b]]) if (rawHome[k] && (!home.has(g) || k === g)) home.set(g, rawHome[k]);
    const type = kind(e.r);
    const [x, y] = DIRECTED.has(type) ? [a, b] : a < b ? [a, b] : [b, a];
    const pair = a < b ? `${a}|${b}` : `${b}|${a}`;
    let links = byPair.get(pair); if (!links) byPair.set(pair, (links = new Map()));
    const id = `${type}:${x}>${y}`;
    let l = links.get(id);
    if (!l) links.set(id, (l = { id, a: x, b: y, type, roles: [], lists: [], share: e.p, start: e.s, end: e.e }));
    if (e.l && !l.roles.includes(e.l)) l.roles.push(e.l);
    const list = e.f.slice(0, e.f.indexOf('/'));
    if (!l.lists.includes(list)) l.lists.push(list);
    if (e.p != null && l.share == null) l.share = e.p;
  }
  const links = new Map<string, Link[]>();
  const add = (g: string, l: Link) => { let ls = links.get(g); if (!ls) links.set(g, (ls = [])); ls.push(l); };
  byPair.forEach(pair => {
    const all = [...pair.values()];
    // A typed link says more than a plain one between the same two entities
    const keep = all.some(l => l.type !== 'LINKED') ? all.filter(l => l.type !== 'LINKED') : all;
    keep.forEach(l => { add(l.a, l); add(l.b, l); });
  });
  return { links, home };
}

let pending: Promise<Graph> | null = null;
let pendingFor: Index | null = null;

/** Loads relations.json once per index. A snapshot written before the file existed has no links. */
export function loadGraph(ix: Index): Promise<Graph> {
  if (!pending || pendingFor !== ix) {
    pendingFor = ix;
    pending = fetchJson<RawRelations>('relations.json')
      .catch(e => { if (String(e).includes('HTTP 404')) return { formatVersion: 1, generatedAt: '' } as RawRelations; throw e; })
      .then(raw => buildGraph(raw, ix))
      .catch(e => { pending = null; throw e; });
  }
  return pending;
}

/** The entity at the other end of a link. */
export const other = (l: Link, g: string) => (l.a === g ? l.b : l.a);

/** How a link reads from one of its ends, such as "owns" or "owned by". */
export function phrase(l: Link, from: string): string {
  if (l.type === 'OWNERSHIP') return l.a === from ? 'owns' : 'owned by';
  if (l.type === 'DIRECTORSHIP') return l.a === from ? 'director or officer of' : 'has as director or officer';
  if (l.type === 'FAMILY') return 'family of';
  if (l.type === 'ASSOCIATE') return 'associate of';
  return 'linked to';
}
