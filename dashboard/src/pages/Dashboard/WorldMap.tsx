import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as d3 from 'd3';
import * as topojson from 'topojson-client';
import type { Feature, Geometry } from 'geojson';
import type { Topology, GeometryCollection } from 'topojson-specification';
import WORLD from 'world-atlas/countries-110m.json?url';
import { Flag } from '../../components/Flag';
import { SectionLink } from '../../components/Header';
import { HBar } from '../../components/HBar';
import { SlideOver } from '../../components/SlideOver';
import { Chip, StatusDot } from '../../components/Badges';
import { Icon } from '../../lib/icons';
import { SIEVE, TYPE_LABEL, TYPES, type Country } from '../../data/snapshot';
import { ISO_NUMERIC, UNNUMBERED_FEATURES, countryName } from '../../data/iso';
import { TCOL } from './Composition';
import { cssVar, fmt } from '../../lib/format';
import { useTheme } from '../../lib/theme';

type Tab = 'targets' | 'authorities' | 'flows';
type Basis = 'nationality' | 'address' | 'both';
type TypeF = 'all' | 'individual' | 'entity' | 'vessel' | 'aircraft';
type F = Feature<Geometry, { name?: string }> & { id: string };

const ISO_BY_NUMERIC = Object.fromEntries(Object.entries(ISO_NUMERIC).map(([a, n]) => [n, a]));
const NONE = { individual: 0, entity: 0, vessel: 0, aircraft: 0 };
/** The snapshot row of a map feature, or an empty row for a country no list links to. */
const countryOf = (f: F): Country | undefined => {
  const c = SIEVE.countryByNum[f.id]; if (c) return c;
  const cc = ISO_BY_NUMERIC[f.id]; return cc ? { cc, num: f.id, name: countryName(cc), total: 0, nationality: 0, address: 0, bySource: {}, byType: NONE } : undefined;
};

/** Entities of a country on the selected basis, all sources and types. */
const onBasis = (c: Country, b: Basis) => (b === 'both' ? c.total : b === 'nationality' ? c.nationality : c.address);

export function WorldMap() {
  const D = SIEVE; const { theme } = useTheme(); const nav = useNavigate();
  const ALL_SOURCES = useMemo(() => D.sources.filter(s => s.entities).map(s => s.id), [D]);
  const [tab, setTab] = useState<Tab>('targets');
  const [sources, setSources] = useState<Set<string>>(new Set(ALL_SOURCES));
  const [type, setType] = useState<TypeF>('all');
  const [basis, setBasis] = useState<Basis>('both');
  const [asTable, setAsTable] = useState(false);
  const [ddOpen, setDdOpen] = useState(false);
  const [features, setFeatures] = useState<F[] | null>(null);
  const [geoErr, setGeoErr] = useState(false);
  const [tip, setTip] = useState<{ x: number; y: number; c: Country; v: number } | null>(null);
  const [sel, setSel] = useState<Country | null>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const zoomRef = useRef<d3.ZoomBehavior<SVGSVGElement, unknown>>();
  const [size, setSize] = useState({ w: 800, h: 480 });

  useEffect(() => { fetch(WORLD).then(r => r.json()).then((t: Topology) => setFeatures((topojson.feature(t, t.objects.countries as GeometryCollection) as unknown as { features: F[] }).features.map(f => ({ ...f, id: f.id ?? UNNUMBERED_FEATURES[f.properties?.name ?? ''] ?? '' })))).catch(() => setGeoErr(true)); }, []);
  useEffect(() => { const el = wrap.current; if (!el) return; const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight })); ro.observe(el); return () => ro.disconnect(); }, []);
  useEffect(() => { const h = (e: MouseEvent) => { if (!(e.target as Element).closest('.dd')) setDdOpen(false); }; document.addEventListener('click', h); return () => document.removeEventListener('click', h); }, []);

  const allSources = sources.size === ALL_SOURCES.length;
  // Exact for the default view. The snapshot has no cross-tab of source, type and basis, so a
  // combination of those filters is scaled from the per-country totals.
  const estimated = (type !== 'all' ? 1 : 0) + (allSources ? 0 : 1) + (basis !== 'both' ? 1 : 0) > 1;
  const cval = useCallback((c: Country) => {
    if (!c.total) return 0;
    let v = onBasis(c, basis);
    if (!allSources) { let n = 0; sources.forEach(s => (n += c.bySource[s] || 0)); v = basis === 'both' ? n : n * (v / c.total); }
    if (type !== 'all') v = (v * c.byType[type]) / c.total;
    return Math.round(v);
  }, [sources, allSources, type, basis]);

  const geo = useMemo(() => {
    if (!features) return null;
    const { w, h } = size;
    const projection = d3.geoNaturalEarth1().fitExtent([[8, 8], [w - 8, h - 8]], { type: 'Sphere' });
    const path = d3.geoPath(projection);
    const vals: Record<string, number> = {}; let max = 1;
    const auth = tab === 'authorities';
    features.forEach(f => { const c = D.countryByNum[f.id]; const v = auth ? (D.authorities[ISO_BY_NUMERIC[f.id]] || []).length : c ? cval(c) : 0; vals[f.id] = v; if (v > max) max = v; });
    const color = auth ? d3.scaleLinear<string>().domain([0, 1, 5]).range([cssVar('--map-empty'), cssVar('--map-1'), cssVar('--map-2')]).interpolate(d3.interpolateRgb) : d3.scaleSequentialSqrt(d3.interpolateRgbBasis([cssVar('--map-0'), cssVar('--map-1'), cssVar('--map-2')])).domain([0, max]);
    const cent: Record<string, [number, number]> = {}; features.forEach(f => { const cc = ISO_BY_NUMERIC[f.id]; if (cc) cent[cc] = d3.geoCentroid(f); });
    const flows = D.flows.filter(f => cent[f[0]] && cent[f[1]]); const fmax = d3.max(flows, f => f[2]) ?? 1;
    return { projection, path, vals, max, color, cent, flows, fmax, sphere: path({ type: 'Sphere' }) ?? '' };
  }, [features, size, tab, cval, D, theme]);

  useEffect(() => {
    if (!svgRef.current || !geo) return;
    const svg = d3.select(svgRef.current), g = svg.select<SVGGElement>('g.zoom');
    const zoom = d3.zoom<SVGSVGElement, unknown>().scaleExtent([1, 8]).translateExtent([[0, 0], [size.w, size.h]]).on('zoom', e => g.attr('transform', e.transform));
    svg.call(zoom); zoomRef.current = zoom;
  }, [geo, size]);
  const zoomBy = (k: number) => { if (svgRef.current && zoomRef.current) d3.select(svgRef.current).transition().call(zoomRef.current.scaleBy, k); };
  const reset = () => { if (svgRef.current && zoomRef.current) d3.select(svgRef.current).transition().call(zoomRef.current.transform, d3.zoomIdentity); };

  const onMove = (e: React.MouseEvent, f: F) => {
    const c = countryOf(f); if (!c || !wrap.current || !geo) { setTip(null); return; }
    const r = wrap.current.getBoundingClientRect(); let x = e.clientX - r.left + 14, y = e.clientY - r.top + 14;
    if (x > r.width - 280) x -= 294; if (y > r.height - 220) y -= 234;
    setTip({ x, y, c, v: geo.vals[f.id] });
  };
  const toggleSource = (id: string) => setSources(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const tableRows = useMemo(() => D.countries.map(c => [c, cval(c)] as const).filter(r => r[1] > 0).sort((a, b) => b[1] - a[1]).slice(0, 60), [cval, D]);
  const u = D.unresolved;

  return (
    <div className="card">
      <div className="filters">
        <div className="tabs" role="tablist" style={{ border: 0 }}>
          {(['targets', 'authorities', 'flows'] as Tab[]).map(t => <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>)}
        </div>
        <span className="sp" />
        <div className={'dd' + (ddOpen ? ' open' : '')}>
          <button className="btn sm" aria-haspopup="true" aria-expanded={ddOpen} onClick={() => setDdOpen(o => !o)}>Sources · {allSources ? `all ${ALL_SOURCES.length}` : sources.size} <Icon name="down" size={12} /></button>
          <div className="dd-m">
            <label><input type="checkbox" checked={sources.size === ALL_SOURCES.length} onChange={e => setSources(new Set(e.target.checked ? ALL_SOURCES : []))} /> <b>All sources</b></label>
            {ALL_SOURCES.map(id => { const s = D.byId[id]; return <label key={id}><input type="checkbox" checked={sources.has(id)} onChange={() => toggleSource(id)} /><Flag cc={s.cc} /> {s.name}<span className="cnt num">{fmt(s.entities)}</span></label>; })}
          </div>
        </div>
        <select className="sel" aria-label="Entity type" style={{ height: 28, fontSize: 12 }} value={type} onChange={e => setType(e.target.value as TypeF)}><option value="all">All types</option><option value="individual">Individuals</option><option value="entity">Entities</option><option value="vessel">Vessels</option><option value="aircraft">Aircraft</option></select>
        <div className="seg" role="group" aria-label="Basis">{(['nationality', 'address', 'both'] as Basis[]).map(b => <button key={b} aria-pressed={basis === b} onClick={() => setBasis(b)}>{b[0].toUpperCase() + b.slice(1)}</button>)}</div>
      </div>

      <div className="map-wrap" ref={wrap} style={{ display: asTable ? 'none' : undefined }}>
        {geo && (
          <svg ref={svgRef} viewBox={`0 0 ${size.w} ${size.h}`} role="img" aria-label="World map of sanctioned entities per country">
            <g className="zoom">
              <path d={geo.sphere} fill="none" stroke="var(--border)" />
              {features!.map(f => { const c = countryOf(f); const v = geo.vals[f.id]; return (
                <path key={f.id} d={geo.path(f) ?? ''} fill={v ? geo.color(v) : 'var(--map-empty)'} stroke="var(--map-stroke)" strokeWidth={0.5} opacity={tab === 'flows' ? 0.55 : 1} style={{ cursor: c ? 'pointer' : 'default' }}
                  onMouseMove={e => onMove(e, f)} onMouseLeave={() => setTip(null)} onClick={() => c && setSel(c)} />
              ); })}
              {tab === 'flows' && <>
                {geo.flows.map((f, i) => <path key={i} d={geo.path({ type: 'LineString', coordinates: [geo.cent[f[0]], geo.cent[f[1]]] }) ?? ''} fill="none" stroke="var(--accent)" strokeWidth={0.6 + (3 * f[2]) / geo.fmax} strokeOpacity={0.75} strokeLinecap="round" />)}
                {Object.keys(D.authorities).filter(c => geo.cent[c]).map(c => { const p = geo.projection(geo.cent[c])!; return <circle key={c} cx={p[0]} cy={p[1]} r={3} fill="var(--accent)" />; })}
              </>}
            </g>
          </svg>
        )}
        {!geo && !geoErr && <div className="empty"><div className="skl" style={{ width: 160, height: 12, margin: '0 auto' }} /></div>}
        {geoErr && <div className="empty"><Icon name="globe" size={36} /><h3>Map geometry unavailable</h3>Could not load Natural Earth data. Use “Show as table”.</div>}
        <div className="map-ctl"><button aria-label="Zoom in" onClick={() => zoomBy(1.6)}>+</button><button aria-label="Zoom out" onClick={() => zoomBy(1 / 1.6)}>−</button><button aria-label="Reset zoom" style={{ fontSize: 11 }} onClick={reset}>⟲</button></div>
        {geo && <div className="legend">{tab === 'authorities' ? <><span>Lists issued</span><span className="bar" style={{ background: `linear-gradient(90deg,${cssVar('--map-1')},${cssVar('--map-2')})` }} /><span className="num">1 → 5</span></>
          : <><span className="num">0</span><span className="bar" style={{ background: `linear-gradient(90deg,${cssVar('--map-0')},${cssVar('--map-1')},${cssVar('--map-2')})` }} /><span className="num">{fmt(geo.max)}</span>{tab === 'flows' && <span style={{ marginLeft: 8, display: 'inline-flex', alignItems: 'center', gap: 5 }}><i style={{ width: 14, height: 2, background: 'var(--accent)', display: 'inline-block' }} />designations</span>}</>}</div>}
        {tip && <Tooltip tip={tip} tab={tab} sources={sources} />}
      </div>

      {asTable && (
        <div style={{ overflowX: 'auto' }}>
          <table className="tbl"><thead><tr><th>#</th><th>Country</th><th className="r">Shown</th><th className="r">By nationality</th><th className="r">By address</th><th className="r">Individuals</th><th className="r">Organisations</th><th className="r">Vessels</th><th>Top source</th></tr></thead>
            <tbody>{tableRows.map(([c, v], i) => { const top = Object.entries(c.bySource).filter(([id]) => sources.has(id)).sort((a, b) => b[1] - a[1])[0]; return (
              <tr key={c.cc} className="row" onClick={() => setSel(c)}><td className="num muted">{i + 1}</td><td><span className="cl"><Flag cc={c.cc} />{c.name}</span></td><td className="r num">{fmt(v)}</td><td className="r num">{fmt(c.nationality)}</td><td className="r num">{fmt(c.address)}</td><td className="r num">{fmt(c.byType.individual)}</td><td className="r num">{fmt(c.byType.entity)}</td><td className="r num">{fmt(c.byType.vessel)}</td><td>{top ? D.byId[top[0]]?.name ?? top[0] : '—'}</td></tr>
            ); })}</tbody></table>
        </div>
      )}

      <div className="map-foot">
        <span>{estimated ? <>Combined filters are <b style={{ color: 'var(--text)' }}>estimated</b> from per-country totals · </> : null}<span className="num">{fmt(u.occurrences)}</span> country values unresolved{u.top.length > 0 && <> ({u.top.slice(0, 3).map(t => t[0]).join(', ')}{u.top.length > 3 ? ', …' : ''})</>}</span>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}><input type="checkbox" checked={asTable} onChange={e => setAsTable(e.target.checked)} /> Show as table</label>
      </div>

      <SlideOver open={!!sel} onClose={() => setSel(null)} title={sel && <><Flag cc={sel.cc} />{sel.name}</>}
        aside={sel && (tab === 'authorities' ? <span className="badge acc">Authority</span> : <Chip>{sel.cc}</Chip>)}
        footer={sel && (tab === 'authorities' ? <SectionLink id="sources" className="btn" onClick={() => setSel(null)}>Open sources table</SectionLink> : <><button className="btn pri" onClick={() => nav(`/search?country=${sel.cc}`)}>View entities <Icon name="right" /></button><button className="btn" onClick={() => setSel(null)}>Close</button></>)}>
        {sel && (tab === 'authorities' ? <AuthorityPanel c={sel} /> : <CountryPanel c={sel} v={cval(sel)} sources={sources} basis={basis} type={type} />)}
      </SlideOver>
    </div>
  );
}

function Tooltip({ tip, tab, sources }: { tip: { x: number; y: number; c: Country; v: number }; tab: Tab; sources: Set<string> }) {
  const D = SIEVE, { c, v } = tip;
  const style = { left: tip.x, top: tip.y };
  if (tab === 'authorities') {
    const ls = D.authorities[c.cc] || [];
    return <div className="tip on" style={style}><div className="t"><Flag cc={c.cc} />{c.name}</div><div className="n num">{ls.length}<span className="small muted" style={{ fontSize: 12 }}> list{ls.length === 1 ? '' : 's'} issued</span></div>{ls.length ? ls.map(id => <div key={id} className="row"><span>{D.byId[id].name}</span><span className="num">{fmt(D.byId[id].entities)}</span></div>) : <div className="muted">No lists ingested from this jurisdiction</div>}</div>;
  }
  const top = Object.entries(c.bySource).filter(([k]) => sources.has(k)).sort((a, b) => b[1] - a[1]).slice(0, 3); const t = c.byType, tt = c.total || 1;
  const pct = (n: number) => Math.round((n / tt) * 100);
  return (
    <div className="tip on" style={style}>
      <div className="t"><Flag cc={c.cc} />{c.name}</div><div className="n num">{fmt(v)}<span className="small muted" style={{ fontSize: 12 }}> entities</span></div>
      <div className="xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.04em' }}>Top sources</div>
      {top.map(([k, n]) => <div key={k} className="row"><span>{D.byId[k]?.name ?? k}</span><span className="num">{fmt(n)}</span></div>)}
      <div className="xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.04em', marginTop: 6 }}>Type split</div>
      <div className="bar"><i style={{ width: `${pct(t.individual)}%`, background: 'var(--map-1)' }} /><i style={{ width: `${pct(t.entity)}%`, background: 'var(--accent)' }} /><i style={{ width: `${pct(t.vessel)}%`, background: 'var(--amber)' }} /><i style={{ width: `${pct(t.aircraft)}%`, background: 'var(--red)' }} /></div>
      <div className="row xs"><span>Ind. {pct(t.individual)}%</span><span>Ent. {pct(t.entity)}%</span><span>Ves. {pct(t.vessel)}%</span><span>Air. {pct(t.aircraft)}%</span></div>
    </div>
  );
}

function CountryPanel({ c, v, sources, basis, type }: { c: Country; v: number; sources: Set<string>; basis: Basis; type: TypeF }) {
  const D = SIEVE;
  const src = Object.entries(c.bySource).filter(([id]) => sources.has(id)).sort((a, b) => b[1] - a[1]).slice(0, 10), smax = src[0]?.[1] || 1;
  const tmax = Math.max(1, ...TYPES.map(t => c.byType[t]));
  const K = ({ l, v }: { l: string; v: string }) => <div className="card kpi"><div className="l">{l}</div><div className="v num" style={{ fontSize: 22 }}>{v}</div></div>;
  return (
    <>
      <div className="so-sec" style={{ display: 'grid', gridTemplateColumns: 'repeat(2,1fr)', gap: 12 }}>
        <K l="Shown on map" v={fmt(v)} /><K l="Share of all entities" v={((c.total / (D.totalEntities || 1)) * 100).toFixed(1) + '%'} /><K l="By nationality" v={fmt(c.nationality)} /><K l="By address" v={fmt(c.address)} />
      </div>
      <div className="so-sec"><h4>Per source</h4>{src.map(([id, n]) => <HBar key={id} label={<><Flag cc={D.byId[id]?.cc} />{D.byId[id]?.name ?? id}</>} pct={(n / smax) * 100} value={fmt(n)} />)}{!src.length && <span className="muted small">No selected source lists this country.</span>}</div>
      <div className="so-sec"><h4>Entity types</h4>{TYPES.map(t => <HBar key={t} label={<><i style={{ width: 8, height: 8, borderRadius: 2, background: TCOL[t], display: 'inline-block' }} />{TYPE_LABEL[t]}</>} pct={(c.byType[t] / tmax) * 100} value={fmt(c.byType[t])} />)}</div>
      <div className="so-sec"><h4>Basis</h4><span className="small muted">Counted by {basis === 'both' ? 'nationality or address' : basis}{type !== 'all' && `, ${TYPE_LABEL[type].toLowerCase()}s only`}. An entity linked to several countries counts once for each.</span></div>
    </>
  );
}

function AuthorityPanel({ c }: { c: Country }) {
  const D = SIEVE, ls = D.authorities[c.cc] || [];
  return (
    <div className="so-sec"><h4>Lists issued ({ls.length})</h4>
      {ls.length ? ls.map(id => { const s = D.byId[id]; return (
        <div key={id} style={{ padding: '10px 0', borderBottom: '1px solid var(--border)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}><b>{s.name}</b><span className="num">{fmt(s.entities)}</span></div>
          <div className="small muted">{s.authority}</div>
          <div style={{ display: 'flex', gap: 6, marginTop: 6 }}><Chip>{s.format}</Chip><StatusDot status={s.status} /></div>
        </div>
      ); }) : <span className="muted">Sieve ingests no list from this jurisdiction.</span>}
    </div>
  );
}
