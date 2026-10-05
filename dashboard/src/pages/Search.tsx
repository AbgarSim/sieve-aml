import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { Badge, TopicBadge, TopicBadges, TypeBadge } from '../components/Badges';
import { Icon, TYPE_ICON } from '../lib/icons';
import { SIEVE, TYPE_LABEL, byTopicOrder, type EntityType } from '../data/snapshot';
import { search, type Entry, type Hit } from '../data/search';
import { useIndex } from '../data/useIndex';
import { countryName } from '../data/iso';
import { fmt } from '../lib/format';
import { jaroWinkler as jw, normalize as norm, tokens } from '../lib/jw';

type FacetKey = 'source' | 'topic' | 'type' | 'country' | 'program';
type Filters = Record<FacetKey, Set<string>>;
const SHOWN = 100;
const EXAMPLES = ['Dubrovin', 'Victor Dubrovine', 'Caspian Dawn'];

function Highlight({ text, q }: { text: string; q: string }) {
  const qt = tokens(norm(q));
  return <>{text.split(/(\s+)/).map((w, i) => { const wn = norm(w); const hit = wn && qt.some(t => jw(t, wn) >= 0.85 || (wn.includes(t) && t.length > 2)); return hit ? <mark key={i}>{w}</mark> : <span key={i}>{w}</span>; })}</>;
}

export const entityPath = (e: Entry) => `/entity/${e.source}/${encodeURIComponent(e.id)}`;

/** Hits for one entity's records on several lists, best first. */
interface Group extends Hit { all: Entry[] }

/** Folds the per-list records of one entity into a single result row, keeping list order by score. */
function group(hits: Hit[]): Group[] {
  const by = new Map<string, Group>();
  for (const h of hits) {
    const g = by.get(h.e.group);
    if (g) g.all.push(h.e); else by.set(h.e.group, { ...h, all: [h.e] });
  }
  return [...by.values()];
}

export default function Search() {
  const D = SIEVE;
  const { ix, error } = useIndex();
  const [sp, setSp] = useSearchParams();
  const [q, setQ] = useState(sp.get('q') ?? '');
  const [debounced, setDebounced] = useState(q);
  const [th, setTh] = useState(0.85);
  const param = (k: string) => new Set(sp.get(k) ? sp.get(k)!.split(',') : []);
  const [f, setF] = useState<Filters>({ source: param('source'), topic: param('topic'), type: new Set(), country: param('country'), program: new Set() });
  const [more, setMore] = useState<Partial<Record<FacetKey, boolean>>>({});
  const inp = useRef<HTMLInputElement>(null);
  const loading = !ix || q !== debounced;
  useEffect(() => { const t = setTimeout(() => setDebounced(q), 300); return () => clearTimeout(t); }, [q]);
  useEffect(() => { const n = new URLSearchParams(sp); debounced ? n.set('q', debounced) : n.delete('q'); setSp(n, { replace: true }); }, [debounced]); // eslint-disable-line react-hooks/exhaustive-deps

  const anyFilter = Object.values(f).some(s => s.size);
  const { hits, took } = useMemo(() => {
    const t0 = performance.now(), qq = debounced.trim();
    const h: Hit[] = !ix ? [] : qq ? search(ix, qq, th) : anyFilter ? ix.entries.map(e => ({ e, s: 0, name: e.name })) : [];
    return { hits: h, took: (performance.now() - t0).toFixed(0) };
  }, [ix, debounced, th, anyFilter]);
  const pass = (h: Hit, skip?: FacetKey) => (skip === 'source' || !f.source.size || f.source.has(h.e.source)) && (skip === 'topic' || !f.topic.size || h.e.topics.some(t => f.topic.has(t))) && (skip === 'type' || !f.type.size || f.type.has(h.e.type)) && (skip === 'country' || !f.country.size || h.e.countries.some(c => f.country.has(c))) && (skip === 'program' || !f.program.size || h.e.programs.some(p => f.program.has(p)));
  const shown = useMemo(() => group(hits.filter(h => pass(h))), [hits, f]); // eslint-disable-line react-hooks/exhaustive-deps
  const toggle = (k: FacetKey, v: string) => setF(o => { const n = new Set(o[k]); n.has(v) ? n.delete(v) : n.add(v); return { ...o, [k]: n }; });
  const clear = (k: FacetKey) => setF(o => ({ ...o, [k]: new Set() }));
  const count = (key: FacetKey, get: (e: Entry) => string[]) => { const m: Record<string, number> = {}; hits.forEach(h => { if (pass(h, key)) new Set(get(h.e)).forEach(k => (m[k] = (m[k] || 0) + 1)); }); return Object.entries(m).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])); };
  const groups: [FacetKey, string, [string, number][], (k: string) => ReactNode][] = [
    ['source', 'Source', count('source', e => [e.source]), k => <><Flag cc={D.byId[k]?.cc} /> {D.byId[k]?.name ?? k}</>],
    ['topic', 'Topic', count('topic', e => e.topics).sort((a, b) => byTopicOrder(a[0], b[0])), k => <TopicBadge topic={k} />],
    ['type', 'Entity type', count('type', e => [e.type]), k => <><Icon name={TYPE_ICON[k as EntityType]} size={13} style={{ color: 'var(--muted)' }} /> {TYPE_LABEL[k as EntityType]}</>],
    ['country', 'Country', count('country', e => e.countries), k => <><Flag cc={k} /> {D.countryByCc[k]?.name ?? countryName(k)}</>],
    ['program', 'Program', count('program', e => e.programs), k => <span className="num" style={{ fontSize: 12 }}>{k}</span>],
  ];
  const set = (v: string) => { setQ(v); inp.current?.focus(); };
  const examples = D.snapshot.sample ? EXAMPLES : [];

  return (
    <>
      <Header active="Search" hideSearch />
      <main className="wrap page">
        <div style={{ maxWidth: 760, margin: '0 auto 28px' }}>
          <h1 style={{ marginBottom: 12 }}>Screen a name</h1>
          <form className="bigsearch" role="search" onSubmit={e => e.preventDefault()}>
            <Icon name="search" size={20} /><input id="big-q" ref={inp} value={q} onChange={e => setQ(e.target.value)} placeholder="Name or alias, in any script… typos are fine" autoComplete="off" aria-label="Search" /><span className="kbd">/</span>
            {q && <button type="button" className="btn sm" onClick={() => set('')}>Clear</button>}
          </form>
          <div className="small muted" style={{ marginTop: 8, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>Jaro-Winkler fuzzy match, threshold <span className="num">{th.toFixed(2)}</span><input type="range" min={70} max={95} value={th * 100} onChange={e => setTh(+e.target.value / 100)} style={{ width: 110, accentColor: 'var(--accent)' }} aria-label="Match threshold" /><span style={{ marginLeft: 'auto' }} className="num">{!ix ? (error ? 'Index unavailable' : 'Loading index…') : debounced ? `${fmt(shown.length)} match${shown.length === 1 ? '' : 'es'} · ${took} ms` : `${fmt(ix.entries.length)} records indexed`}</span></div>
        </div>
        <div className="sgrid">
          <aside>
            {groups.map(([key, title, items, lab]) => { const sel = f[key], lim = more[key] ? items.length : 6; const rows = items.filter(([k]) => sel.has(k)).concat(items.filter(([k]) => !sel.has(k))).slice(0, Math.max(lim, sel.size)); return (
              <div className="facet" key={key}>
                <h4>{title}{sel.size > 0 && <button className="xs" style={{ color: 'var(--link)', textTransform: 'none', letterSpacing: 0, marginLeft: 6 }} onClick={() => clear(key)}>clear</button>}</h4>
                {rows.map(([k, n]) => <label key={k}><input type="checkbox" checked={sel.has(k)} onChange={() => toggle(key, k)} /><span className="cl" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{lab(k)}</span><span className="cnt num">{fmt(n)}</span></label>)}
                {items.length > 6 && <button className="more" onClick={() => setMore(m => ({ ...m, [key]: !m[key] }))}>{more[key] ? 'Show fewer' : `Show all ${items.length}`}</button>}
                {!items.length && <span className="xs muted">{debounced || anyFilter ? 'No values' : 'Search to see values'}</span>}
              </div>
            ); })}
          </aside>
          <div className="card">
            {error ? (
              <div className="empty"><Icon name="warn" size={36} /><h3>Search index unavailable</h3><p>{error}</p></div>
            ) : loading ? Array.from({ length: 5 }).map((_, i) => <div className="res" key={i}><div className="skl" style={{ width: 34, height: 34, flex: 'none' }} /><div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}><div className="skl" style={{ height: 14, width: '45%' }} /><div className="skl" style={{ height: 11, width: '30%' }} /><div style={{ display: 'flex', gap: 6 }}><div className="skl" style={{ height: 20, width: 70 }} /><div className="skl" style={{ height: 20, width: 90 }} /><div className="skl" style={{ height: 20, width: 60 }} /></div></div></div>)
            : !debounced && !anyFilter ? (
              <div className="empty"><Icon name="search" size={36} /><h3>Screen a name against {D.sources.filter(s => !s.countsOnly).length} sanctions lists</h3><p>Every name and alias in tonight's snapshot is searchable, with typo tolerance.{D.unpublished > 0 && <> Politically exposed persons and their relatives and close associates are counted on the overview but not searchable here.</>}{examples.length > 0 && <> Try {examples.map((x, i) => <span key={x}>{i > 0 && ', '}<button className="xs" style={{ color: 'var(--link)' }} onClick={() => set(x)}>{x}</button></span>)}.</>}</p></div>
            ) : !shown.length ? (
              <div className="empty"><Icon name="funnel" size={36} /><h3>No matches{debounced && ` for “${debounced}”`}</h3><p>Lower the threshold, check the spelling, or <button className="xs" style={{ color: 'var(--link)' }} onClick={() => setF({ source: new Set(), topic: new Set(), type: new Set(), country: new Set(), program: new Set() })}>clear filters</button>.</p></div>
            ) : (
              <>
                <div className="card-h"><h3>{debounced ? 'Results' : 'Filtered records'}</h3><span className="xs muted">{shown.length > SHOWN ? `first ${SHOWN} of ${fmt(shown.length)}` : `${shown.length} shown`}{debounced && ' · sorted by score'}</span></div>
                {shown.slice(0, SHOWN).map(({ e, s, name, all }) => { const alias = debounced && name !== e.name ? name : null; const countries = [...new Set(all.flatMap(x => x.countries))], topics = all.flatMap(x => x.topics), programs = [...new Set(all.flatMap(x => x.programs))]; return (
                  <div className="res" key={e.key}>
                    <div className="ic" title={TYPE_LABEL[e.type]}><Icon name={TYPE_ICON[e.type]} /></div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="nm"><Link to={entityPath(e)}>{debounced && !alias ? <Highlight text={e.name} q={debounced} /> : e.name}</Link>{debounced && <span className="num xs muted" style={{ fontWeight: 400, marginLeft: 6 }}>{s.toFixed(2)}</span>}</div>
                      {alias ? <div className="al">matched alias: <Highlight text={alias} q={debounced} /></div> : e.aliases.length > 0 && <div className="al">{e.aliases.length} alias{e.aliases.length === 1 ? '' : 'es'} · {e.aliases[0]}</div>}
                      <div className="meta"><TypeBadge type={e.type} /><TopicBadges topics={topics} />{countries.slice(0, 3).map(c => <Badge key={c} cc={c}>{D.countryByCc[c]?.name ?? countryName(c)}</Badge>)}{countries.length > 3 && <Badge>+{countries.length - 3}</Badge>}{all.slice(0, 4).map(x => <Link key={x.key} to={entityPath(x)}><Badge variant="acc" cc={D.byId[x.source]?.cc}>{D.byId[x.source]?.name ?? x.source}</Badge></Link>)}{all.length > 4 && <Badge>+{all.length - 4} more</Badge>}</div>
                    </div>
                    <div className="dt">{all.length > 1 ? <>{all.length} lists<br /></> : null}{programs.length > 0 ? <span className="num" style={{ color: 'var(--text)' }}>{programs[0]}{programs.length > 1 ? ` +${programs.length - 1}` : ''}</span> : null}</div>
                  </div>
                ); })}
              </>
            )}
          </div>
        </div>
      </main>
      <Footer />
    </>
  );
}
