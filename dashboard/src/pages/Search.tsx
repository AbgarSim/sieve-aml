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
import { countryName, ISO_NUMERIC } from '../data/iso';
import { FIELD_LABEL, hasCriteria, parseDate, type Criteria, type FieldMatch } from '../data/evidence';
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

/** URL parameter of each extra field, so a search with them can be linked. */
const CRIT_PARAM: Record<keyof Criteria, string> = { dob: 'dob', placeOfBirth: 'pob', country: 'cc', address: 'addr', identifier: 'id', gender: 'sex' };
const COUNTRIES = Object.keys(ISO_NUMERIC).map(cc => [cc, countryName(cc)] as const).sort((a, b) => a[1].localeCompare(b[1]));
const OUTCOME: Record<FieldMatch['outcome'], { mark: string; label: string; cls: string }> = {
  MATCH: { mark: '✓', label: 'matches', cls: 'ev-ok' }, PARTIAL: { mark: '≈', label: 'close', cls: 'ev-near' }, MISMATCH: { mark: '✕', label: 'differs', cls: 'ev-bad' }, NOT_ON_RECORD: { mark: '–', label: 'not on record', cls: 'ev-none' },
};

/** How each extra field compared with the record, as small chips. */
function Evidence({ evidence }: { evidence: FieldMatch[] }) {
  return <div className="evidence">{evidence.map(f => { const o = OUTCOME[f.outcome]; return <span key={f.field} className={`ev ${o.cls}`} title={`${FIELD_LABEL[f.field]} ${o.label}${f.value ? `: ${f.value}` : ''}`}><b aria-hidden>{o.mark}</b> {FIELD_LABEL[f.field]}{f.value && f.outcome !== 'MISMATCH' ? <span className="num"> {f.value}</span> : f.outcome === 'MISMATCH' ? <span> differs</span> : <span> not on record</span>}</span>; })}</div>;
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
  const [crit, setCrit] = useState<Criteria>(() => { const c: Criteria = {}; (Object.keys(CRIT_PARAM) as (keyof Criteria)[]).forEach(k => { const v = sp.get(CRIT_PARAM[k]); if (v) (c as Record<string, string>)[k] = v; }); return c; });
  const [adv, setAdv] = useState(() => hasCriteria(crit));
  const [critD, setCritD] = useState(crit);
  const loading = !ix || q !== debounced || crit !== critD;
  useEffect(() => { const t = setTimeout(() => { setDebounced(q); setCritD(crit); }, 300); return () => clearTimeout(t); }, [q, crit]);
  useEffect(() => {
    const n = new URLSearchParams(sp); debounced ? n.set('q', debounced) : n.delete('q');
    (Object.keys(CRIT_PARAM) as (keyof Criteria)[]).forEach(k => { const v = critD[k]?.trim(); v ? n.set(CRIT_PARAM[k], v) : n.delete(CRIT_PARAM[k]); });
    setSp(n, { replace: true });
  }, [debounced, critD]); // eslint-disable-line react-hooks/exhaustive-deps
  const setField = (k: keyof Criteria, v: string) => setCrit(c => { const n = { ...c }; if (v) (n as Record<string, string>)[k] = v; else delete n[k]; return n; });
  const weighed = hasCriteria(critD);
  const badDob = !!crit.dob?.trim() && !parseDate(crit.dob);
  const searching = !!debounced.trim() || !!critD.identifier?.trim();

  const anyFilter = Object.values(f).some(s => s.size);
  const { hits, took } = useMemo(() => {
    const t0 = performance.now(), qq = debounced.trim();
    const h: Hit[] = !ix ? [] : qq || critD.identifier?.trim() ? search(ix, qq, th, critD) : anyFilter ? ix.entries.map(e => ({ e, s: 0, name: e.name })) : [];
    return { hits: h, took: (performance.now() - t0).toFixed(0) };
  }, [ix, debounced, th, anyFilter, critD]);
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
          <div className="small muted" style={{ marginTop: 8, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}><span>Jaro-Winkler fuzzy match at confidence <span className="num">{th.toFixed(2)}</span>{weighed && <>, weighing {Object.keys(critD).filter(k => critD[k as keyof Criteria]?.trim()).map(k => FIELD_LABEL[k as keyof Criteria].toLowerCase()).join(', ')}</>}</span>
            <button type="button" className="xs adv-toggle" aria-expanded={adv} aria-controls="adv-fields" onClick={() => setAdv(a => !a)}>{adv ? 'Fewer fields' : 'More fields'}{!adv && weighed ? ` (${Object.keys(critD).length})` : ''}</button>
            <span style={{ marginLeft: 'auto' }} className="num">{!ix ? (error ? 'Index unavailable' : 'Loading index…') : searching ? `${fmt(shown.length)} match${shown.length === 1 ? '' : 'es'} · ${took} ms` : `${fmt(ix.entries.length)} records indexed`}</span></div>
          {adv && (
            <div className="adv" id="adv-fields">
              <p className="xs muted adv-note">Each field you fill is weighed against the record: agreement raises the score, a clear difference lowers it, and a field the list does not state changes nothing.</p>
              <label><span>Date of birth</span><input value={crit.dob ?? ''} onChange={e => setField('dob', e.target.value)} placeholder="1970, 1970-05 or 1970-05-12" inputMode="numeric" aria-invalid={badDob} />{badDob && <span className="xs adv-err">Use YYYY, YYYY-MM or YYYY-MM-DD</span>}</label>
              <label><span>Country</span><select value={crit.country ?? ''} onChange={e => setField('country', e.target.value)}><option value="">Any</option>{COUNTRIES.map(([cc, n]) => <option key={cc} value={cc}>{n}</option>)}</select></label>
              <label><span>ID number</span><input value={crit.identifier ?? ''} onChange={e => setField('identifier', e.target.value)} placeholder="Passport, national ID, tax ID, LEI…" autoComplete="off" /></label>
              <label><span>Place of birth</span><input value={crit.placeOfBirth ?? ''} onChange={e => setField('placeOfBirth', e.target.value)} placeholder="City" /></label>
              <label><span>Address</span><input value={crit.address ?? ''} onChange={e => setField('address', e.target.value)} placeholder="Street, city" /></label>
              <label><span>Gender</span><select value={crit.gender ?? ''} onChange={e => setField('gender', e.target.value)}><option value="">Any</option><option value="M">Male</option><option value="F">Female</option><option value="O">Other</option></select></label>
              {hasCriteria(crit) && <button type="button" className="xs adv-clear" onClick={() => setCrit({})}>Clear fields</button>}
            </div>
          )}
        </div>
        <div className="sgrid">
          <aside>
            <details className="facet conf">
              <summary><h4>Confidence</h4><span className="num">{th.toFixed(2)}</span></summary>
              <input type="range" min={70} max={95} value={th * 100} onChange={e => setTh(+e.target.value / 100)} aria-label="Confidence" />
              <div className="xs muted conf-scale"><span>0.70 · more matches</span><span>0.95 · closer matches</span></div>
            </details>
            {groups.map(([key, title, items, lab]) => { const sel = f[key], lim = more[key] ? items.length : 6; const rows = items.filter(([k]) => sel.has(k)).concat(items.filter(([k]) => !sel.has(k))).slice(0, Math.max(lim, sel.size)); return (
              <div className="facet" key={key}>
                <h4>{title}{sel.size > 0 && <button className="xs" style={{ color: 'var(--link)', textTransform: 'none', letterSpacing: 0, marginLeft: 6 }} onClick={() => clear(key)}>clear</button>}</h4>
                {rows.map(([k, n]) => <label key={k}><input type="checkbox" checked={sel.has(k)} onChange={() => toggle(key, k)} /><span className="cl" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{lab(k)}</span><span className="cnt num">{fmt(n)}</span></label>)}
                {items.length > 6 && <button className="more" onClick={() => setMore(m => ({ ...m, [key]: !m[key] }))}>{more[key] ? 'Show fewer' : `Show all ${items.length}`}</button>}
                {!items.length && <span className="xs muted">{searching || anyFilter ? 'No values' : 'Search to see values'}</span>}
              </div>
            ); })}
          </aside>
          <div className="card">
            {error ? (
              <div className="empty"><Icon name="warn" size={36} /><h3>Search index unavailable</h3><p>{error}</p></div>
            ) : loading ? Array.from({ length: 5 }).map((_, i) => <div className="res" key={i}><div className="skl" style={{ width: 34, height: 34, flex: 'none' }} /><div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}><div className="skl" style={{ height: 14, width: '45%' }} /><div className="skl" style={{ height: 11, width: '30%' }} /><div style={{ display: 'flex', gap: 6 }}><div className="skl" style={{ height: 20, width: 70 }} /><div className="skl" style={{ height: 20, width: 90 }} /><div className="skl" style={{ height: 20, width: 60 }} /></div></div></div>)
            : !searching && !anyFilter ? (
              <div className="empty"><Icon name="search" size={36} /><h3>Screen a name against {D.sources.filter(s => !s.countsOnly).length} sanctions lists</h3><p>Every name and alias in tonight's snapshot is searchable, with typo tolerance.{D.unpublished > 0 && <> Politically exposed persons and their relatives and close associates are counted on the overview but not searchable here.</>}{examples.length > 0 && <> Try {examples.map((x, i) => <span key={x}>{i > 0 && ', '}<button className="xs" style={{ color: 'var(--link)' }} onClick={() => set(x)}>{x}</button></span>)}.</>}</p></div>
            ) : !shown.length ? (
              <div className="empty"><Icon name="funnel" size={36} /><h3>No matches{debounced && ` for “${debounced}”`}</h3><p>{weighed && <>A name match whose date of birth, country or gender differs from what you entered scores lower. </>}Lower the confidence under filters, check the spelling, or <button className="xs" style={{ color: 'var(--link)' }} onClick={() => setF({ source: new Set(), topic: new Set(), type: new Set(), country: new Set(), program: new Set() })}>clear filters</button>.</p></div>
            ) : (
              <>
                <div className="card-h"><h3>{searching ? 'Results' : 'Filtered records'}</h3><span className="xs muted">{shown.length > SHOWN ? `first ${SHOWN} of ${fmt(shown.length)}` : `${shown.length} shown`}{debounced && ' · sorted by score'}</span></div>
                {shown.slice(0, SHOWN).map(({ e, s, name, all, nameScore, evidence }) => { const alias = debounced && name !== e.name ? name : null; const countries = [...new Set(all.flatMap(x => x.countries))], topics = all.flatMap(x => x.topics), programs = [...new Set(all.flatMap(x => x.programs))]; return (
                  <div className="res" key={e.key}>
                    <div className="ic" title={TYPE_LABEL[e.type]}><Icon name={TYPE_ICON[e.type]} /></div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="nm"><Link to={entityPath(e)}>{debounced && !alias ? <Highlight text={e.name} q={debounced} /> : e.name}</Link>{searching && <span className="num xs muted" style={{ fontWeight: 400, marginLeft: 6 }} title={nameScore !== undefined ? `Name alone ${nameScore.toFixed(2)}` : undefined}>{nameScore !== undefined && Math.abs(nameScore - s) > 0.005 ? `${nameScore.toFixed(2)} → ` : ''}{s.toFixed(2)}</span>}</div>
                      {alias ? <div className="al">matched alias: <Highlight text={alias} q={debounced} /></div> : e.aliases.length > 0 && <div className="al">{e.aliases.length} alias{e.aliases.length === 1 ? '' : 'es'} · {e.aliases[0]}</div>}
                      {evidence && evidence.length > 0 && <Evidence evidence={evidence} />}
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
