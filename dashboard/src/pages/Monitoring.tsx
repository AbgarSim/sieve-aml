import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import * as d3 from 'd3';
import * as topojson from 'topojson-client';
import type { Feature, Geometry } from 'geojson';
import type { Topology, GeometryCollection } from 'topojson-specification';
import WORLD from 'world-atlas/countries-110m.json?url';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { HBar } from '../components/HBar';
import { StatTile } from '../components/StatTile';
import { Chip, StatusDot } from '../components/Badges';
import { Icon } from '../lib/icons';
import { ISO_NUMERIC, UNNUMBERED_FEATURES, countryName } from '../data/iso';
import { KIND_LABEL, loadEnforcement, money, type Authority, type EnforcementAction, type RawEnforcement, type SuspectedBreach } from '../data/enforcement';
import { PROGRAM_BY_COUNTRY, loadReports, reportLink, type BreachReport } from '../data/reports';
import { cssVar, fmt, host } from '../lib/format';
import { useTheme } from '../lib/theme';

type F = Feature<Geometry, { name?: string }> & { id: string };
type Period = 'all' | '10y' | '5y' | '1y';
type View = 'targets' | 'flows' | 'news' | 'reports';
const ISO_BY_NUMERIC = Object.fromEntries(Object.entries(ISO_NUMERIC).map(([a, n]) => [n, a]));
const AUTHORITIES: Authority[] = ['OFAC', 'OFSI', 'FINCEN', 'DOJ', 'HMRC', 'BIS'];
const ACOL: Record<Authority, string> = { OFAC: 'var(--accent)', OFSI: 'var(--amber)', FINCEN: 'var(--violet)', DOJ: 'var(--red)', HMRC: 'var(--green)', BIS: 'var(--blue)' };
const ALABEL: Record<Authority, string> = { OFAC: 'OFAC', OFSI: 'OFSI', FINCEN: 'FinCEN', DOJ: 'DOJ', HMRC: 'HMRC', BIS: 'BIS' };
const PERIODS: [Period, string][] = [['all', 'All years'], ['10y', '10 years'], ['5y', '5 years'], ['1y', '12 months']];
const PAGE = 50;
const VIEW_LABEL: Record<View, string> = { targets: 'Targeted countries', flows: 'Authority to target', news: 'News reports (unverified)', reports: 'User reports (reviewed)' };
const UNIT: Record<View, [string, string]> = { targets: ['actions', 'No published breach of a program targeting this country'], flows: ['actions', 'No published breach of a program targeting this country'], news: ['news reports, unverified', 'No news report of a breach of a program targeting this country'], reports: ['user reports, reviewed', 'No accepted user report involving this country'] };
const NEWS_DAYS = 90;

const since = (p: Period) => {
  if (p === 'all') return '';
  const d = new Date(); d.setUTCFullYear(d.getUTCFullYear() - (p === '10y' ? 10 : p === '5y' ? 5 : 1));
  return d.toISOString().slice(0, 10);
};

/** Sanctions breaches: published enforcement actions, mapped by the country each breached program targets. */
export default function Monitoring() {
  const [data, setData] = useState<RawEnforcement | null | undefined>(undefined);
  const [err, setErr] = useState<string | null>(null);
  const [reports, setReports] = useState<BreachReport[]>([]);
  useEffect(() => {
    document.title = 'Monitoring — Sieve';
    loadEnforcement().then(setData).catch(e => setErr(String(e)));
    loadReports().then(r => setReports(r.reports)).catch(() => setReports([]));
  }, []);
  return (
    <>
      <Header active="Monitoring" />
      <main className="wrap page">
        <section className="blk">
          <div className="blk-h"><h1>Sanctions breach monitoring</h1><p>Penalties, disclosures and criminal cases for sanctions breaches, as the enforcing authorities publish them{data?.generatedAt ? <> · checked {data.generatedAt.slice(0, 10)}</> : null}</p></div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
              <Link className="btn pri" to={reportLink()}><Icon name="warn" size={16} />Report a suspected breach</Link>
              <span className="small muted">Seen a breach the authorities have not published? Send it with public sources; reviewed reports are added below.</span>
            </div>
          {data === undefined && !err && <div className="card empty"><div className="skl" style={{ width: 160, height: 12, margin: '0 auto' }} /></div>}
          {err && <div className="card empty"><Icon name="warn" size={36} /><h3>Enforcement data unavailable</h3>{err}</div>}
          {data === null && <div className="card empty"><Icon name="info" size={36} /><h3>No enforcement data yet</h3>The nightly snapshot adds it from its next run.</div>}
          {data && <Body d={data} reports={reports} />}
        </section>
      </main>
      <Footer />
    </>
  );
}

function Body({ d, reports }: { d: RawEnforcement; reports: BreachReport[] }) {
  const [params, setParams] = useSearchParams();
  const [auth, setAuth] = useState<Set<Authority>>(new Set(AUTHORITIES));
  const [period, setPeriod] = useState<Period>('all');
  const country = params.get('country');
  const setCountry = (cc: string | null) => setParams(p => { const n = new URLSearchParams(p); cc ? n.set('country', cc) : n.delete('country'); return n; }, { replace: true });
  const from = since(period);
  const shown = useMemo(() => d.actions.filter(a => auth.has(a.source) && a.date >= from), [d, auth, from]);
  const listed = useMemo(() => (country ? shown.filter(a => a.targets.includes(country)) : shown), [shown, country]);
  const newsListed = useMemo(() => { const n = d.suspected ?? []; return country ? n.filter(b => b.targets.includes(country)) : n; }, [d, country]);
  const reportsListed = useMemo(() => (country ? reports.filter(r => r.targets.includes(country)) : reports), [reports, country]);

  const yearAgo = since('1y');
  const sum = (cur: string) => shown.filter(a => a.currency === cur && a.kind === 'CIVIL_PENALTY').reduce((s, a) => s + (a.amount ?? 0), 0);
  const usd = sum('USD'), gbp = sum('GBP');
  const located = shown.filter(a => a.targets.length).length;
  const news = d.suspected ?? [];
  const toggle = (a: Authority) => setAuth(s => { const n = new Set(s); n.has(a) ? n.size > 1 && n.delete(a) : n.add(a); return n; });

  return (
    <>
      {d.sample && <div className="callout" style={{ marginBottom: 16, borderColor: 'var(--amber)' }}><Icon name="warn" size={18} /><span><b>Sample data.</b> Every name and number here is fictional.</span></div>}
      <div className="grid g6" style={{ marginBottom: 20 }}>
        <StatTile label="Actions" value={fmt(shown.length)} note={`${fmt(located)} placed on the map${shown[0] ? `, latest ${shown[0].date}` : ''}`} />
        <StatTile label="Last 12 months" value={fmt(shown.filter(a => a.date >= yearAgo).length)} note="published in the past year" />
        <StatTile label="US civil penalties" value={usd ? money(usd, 'USD') : '—'} note="OFAC, FinCEN and BIS, in USD" />
        <StatTile label="UK penalties" value={gbp ? money(gbp, 'GBP') : '—'} note="OFSI and HMRC, total in GBP" />
        <StatTile label="Criminal cases" value={fmt(shown.filter(a => a.kind === 'CRIMINAL_CASE').length)} note="DOJ, BIS and HMRC releases" />
        <StatTile label="News reports" value={fmt(news.length)} note={`suspected breaches, last ${NEWS_DAYS} days, unverified`} />
      </div>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="filters">
          <div className="seg" role="group" aria-label="Authority">{AUTHORITIES.map(a => <button key={a} aria-pressed={auth.has(a)} onClick={() => toggle(a)}><i style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 2, background: ACOL[a], marginRight: 6 }} />{ALABEL[a]}</button>)}</div>
          <span className="sp" />
          <div className="seg" role="group" aria-label="Period">{PERIODS.map(([p, l]) => <button key={p} aria-pressed={period === p} onClick={() => setPeriod(p)}>{l}</button>)}</div>
        </div>
        <BreachMap actions={shown} news={news} reports={reports} selected={country} onSelect={setCountry} />
      </div>

      <div className="grid g2" style={{ marginBottom: 20 }}>
        <div className="card"><div className="card-h"><h3>Actions per year</h3></div><div className="card-b"><Years actions={shown} /></div></div>
        <div className="card"><div className="card-h"><h3>Programs breached</h3></div><div className="card-b"><Programs actions={shown} /></div></div>
      </div>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="card-h" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h3>{country ? <span className="cl"><Flag cc={country} />Breaches of programs targeting {countryName(country)}</span> : 'Latest actions'}</h3>
          {country && <button className="btn sm" onClick={() => setCountry(null)}>Show all</button>}
          <span className="small muted" style={{ marginLeft: 'auto' }}>{fmt(listed.length)} actions</span>
        </div>
        <Actions actions={listed} />
      </div>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="card-h" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h3>{country ? <span className="cl"><Flag cc={country} />News of suspected breaches involving {countryName(country)}</span> : 'News of suspected breaches'}</h3>
          <span className="badge tp amber">Unverified</span>
          <span className="small muted" style={{ marginLeft: 'auto' }}>{fmt(newsListed.length)} articles</span>
        </div>
        <News items={newsListed} />
        <div className="map-foot"><span>Articles from the GDELT news index whose title names sanctions together with evasion, a violation or an enforcement step, kept for {NEWS_DAYS} days. They report suspicions and allegations, not findings: an article may also be about lawful activity or someone else.</span></div>
      </div>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="card-h" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h3>{country ? <span className="cl"><Flag cc={country} />Reports from users involving {countryName(country)}</span> : 'Reports from users'}</h3>
          <span className="badge tp amber">Reviewed, not an official finding</span>
          <span className="small muted" style={{ marginLeft: 'auto' }}>{fmt(reportsListed.length)} reports</span>
          <Link className="btn sm" to={reportLink(country ? PROGRAM_BY_COUNTRY[country] : undefined)}>Report a suspected breach</Link>
        </div>
        <Reports items={reportsListed} />
        <div className="map-foot"><span>Anyone can report a suspected sanctions breach with the report form, citing public sources; the report is filed as a public issue on GitHub. The maintainer checks each report against its sources, and only accepted reports are shown here, as their reporters wrote them. A report is an allegation backed by the linked sources, not a finding by any authority. To report a crime, contact the enforcing authority.</span></div>
      </div>

      <div className="card">
        <div className="card-h"><h3>Sources</h3></div>
        <div style={{ overflowX: 'auto' }}>
          <table className="tbl"><thead><tr><th>Source</th><th>Authority</th><th className="r">Actions</th><th>Status</th><th>Website</th></tr></thead>
            <tbody>{d.sources.map(s => <tr key={s.id}><td><span className="cl"><Flag cc={s.cc} />{s.name}</span></td><td>{s.authority}</td><td className="r num">{fmt(s.actions)}</td><td><StatusDot status={s.status === 'OK' ? 'loaded' : 'failed'} />{s.error && <span className="small muted"> {s.error}</span>}</td><td><a href={s.url} target="_blank" rel="noopener" className="cl">{host(s.url)} <Icon name="ext" size={12} /></a></td></tr>)}</tbody></table>
        </div>
        <div className="map-foot"><span>A breach is placed on the country its sanctions program targets, as the notice names it; programs against terrorism, narcotics, proliferation or cyber activity have no country. FinCEN enforces anti-money-laundering law, so its actions are counted but placed on the map only when the notice ties a country to sanctions. Criminal cases are the DOJ releases whose title is about breaking sanctions, and the BIS and HMRC releases about export or sanctions prosecutions. HMRC settles trade sanctions and export control offences together, and BIS enforces US export controls, so their actions are counted but placed on the map only when they name a sanctioned country.</span></div>
      </div>
    </>
  );
}

function BreachMap({ actions, news, reports, selected, onSelect }: { actions: EnforcementAction[]; news: SuspectedBreach[]; reports: BreachReport[]; selected: string | null; onSelect: (cc: string | null) => void }) {
  const { theme } = useTheme();
  const [features, setFeatures] = useState<F[] | null>(null);
  const [geoErr, setGeoErr] = useState(false);
  const [view, setView] = useState<View>('targets');
  const [tip, setTip] = useState<{ x: number; y: number; cc: string } | null>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [size, setSize] = useState({ w: 800, h: 480 });
  useEffect(() => { fetch(WORLD).then(r => r.json()).then((t: Topology) => setFeatures((topojson.feature(t, t.objects.countries as GeometryCollection) as unknown as { features: F[] }).features.map(f => ({ ...f, id: f.id ?? UNNUMBERED_FEATURES[f.properties?.name ?? ''] ?? '' })))).catch(() => setGeoErr(true)); }, []);
  useEffect(() => { const el = wrap.current; if (!el) return; const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight })); ro.observe(el); return () => ro.disconnect(); }, []);

  const byCountry = useMemo(() => {
    const m: Record<string, { n: number; by: Record<string, number>; usd: number; gbp: number; last: string }> = {};
    if (view === 'news') { for (const b of news) for (const cc of b.targets) { const r = (m[cc] ??= { n: 0, by: {}, usd: 0, gbp: 0, last: '' }); r.n++; if (b.seenAt.slice(0, 10) > r.last) r.last = b.seenAt.slice(0, 10); } return m; }
    if (view === 'reports') { for (const b of reports) for (const cc of b.targets) { const r = (m[cc] ??= { n: 0, by: {}, usd: 0, gbp: 0, last: '' }); r.n++; if (b.acceptedAt > r.last) r.last = b.acceptedAt; } return m; }
    for (const a of actions) for (const cc of a.targets) {
      const r = (m[cc] ??= { n: 0, by: {}, usd: 0, gbp: 0, last: '' });
      r.n++; r.by[a.source] = (r.by[a.source] ?? 0) + 1;
      if (a.amount && a.kind === 'CIVIL_PENALTY') a.currency === 'GBP' ? (r.gbp += a.amount) : (r.usd += a.amount);
      if (a.date > r.last) r.last = a.date;
    }
    return m;
  }, [actions, news, reports, view]);
  const flows = useMemo(() => { const m = new Map<string, number>(); actions.forEach(a => a.targets.forEach(t => { const k = a.cc + '>' + t; m.set(k, (m.get(k) ?? 0) + 1); })); return [...m].map(([k, n]) => [...k.split('>'), n] as [string, string, number]); }, [actions]);

  const geo = useMemo(() => {
    if (!features) return null;
    const { w, h } = size;
    const projection = d3.geoNaturalEarth1().fitExtent([[8, 8], [w - 8, h - 8]], { type: 'Sphere' });
    const path = d3.geoPath(projection);
    const max = Math.max(1, ...Object.values(byCountry).map(r => r.n));
    const color = d3.scaleSequentialSqrt(d3.interpolateRgbBasis([cssVar('--map-0'), cssVar('--map-1'), cssVar('--map-2')])).domain([0, max]);
    const cent: Record<string, [number, number]> = {}; features.forEach(f => { const cc = ISO_BY_NUMERIC[f.id]; if (cc) cent[cc] = d3.geoCentroid(f); });
    const fl = flows.filter(f => cent[f[0]] && cent[f[1]]); const fmax = d3.max(fl, f => f[2]) ?? 1;
    return { projection, path, max, color, cent, flows: fl, fmax, sphere: path({ type: 'Sphere' }) ?? '' };
  }, [features, size, byCountry, flows, theme]);

  useEffect(() => {
    if (!svgRef.current || !geo) return;
    const svg = d3.select(svgRef.current), g = svg.select<SVGGElement>('g.zoom');
    svg.call(d3.zoom<SVGSVGElement, unknown>().scaleExtent([1, 8]).translateExtent([[0, 0], [size.w, size.h]]).on('zoom', e => g.attr('transform', e.transform)));
  }, [geo, size]);

  const onMove = (e: React.MouseEvent, cc: string | undefined) => {
    if (!cc || !wrap.current) { setTip(null); return; }
    const r = wrap.current.getBoundingClientRect(); let x = e.clientX - r.left + 14, y = e.clientY - r.top + 14;
    if (x > r.width - 280) x -= 294; if (y > r.height - 180) y -= 194;
    setTip({ x, y, cc });
  };
  const t = tip && byCountry[tip.cc];
  return (
    <>
      <div className="tabs" role="tablist" style={{ padding: '0 16px' }}>
        {(['targets', 'flows', 'news', 'reports'] as View[]).map(v => <button key={v} role="tab" aria-selected={view === v} onClick={() => setView(v)}>{VIEW_LABEL[v]}</button>)}
      </div>
      <div className="map-wrap" ref={wrap}>
        {geo && (
          <svg ref={svgRef} viewBox={`0 0 ${size.w} ${size.h}`} role="img" aria-label="World map of sanctions breaches by targeted country">
            <g className="zoom">
              <path d={geo.sphere} fill="none" stroke="var(--border)" />
              {features!.map(f => { const cc = ISO_BY_NUMERIC[f.id]; const v = cc ? byCountry[cc]?.n ?? 0 : 0; return (
                <path key={f.id} d={geo.path(f) ?? ''} fill={v ? geo.color(v) : 'var(--map-empty)'} stroke={cc && cc === selected ? 'var(--heading)' : 'var(--map-stroke)'} strokeWidth={cc && cc === selected ? 1.5 : 0.5} opacity={view === 'flows' ? 0.55 : 1}
                  style={{ cursor: v ? 'pointer' : 'default' }} onMouseMove={e => onMove(e, cc)} onMouseLeave={() => setTip(null)} onClick={() => v && onSelect(cc === selected ? null : cc!)} />
              ); })}
              {view === 'flows' && <>
                {geo.flows.map((f, i) => <path key={i} d={geo.path({ type: 'LineString', coordinates: [geo.cent[f[0]], geo.cent[f[1]]] }) ?? ''} fill="none" stroke={f[0] === 'GB' ? 'var(--amber)' : 'var(--accent)'} strokeWidth={0.6 + (4 * f[2]) / geo.fmax} strokeOpacity={0.7} strokeLinecap="round" />)}
                {[...new Set(geo.flows.map(f => f[0]))].map(c => { const p = geo.projection(geo.cent[c])!; return <circle key={c} cx={p[0]} cy={p[1]} r={4} fill={c === 'GB' ? 'var(--amber)' : 'var(--accent)'} />; })}
              </>}
            </g>
          </svg>
        )}
        {!geo && !geoErr && <div className="empty"><div className="skl" style={{ width: 160, height: 12, margin: '0 auto' }} /></div>}
        {geoErr && <div className="empty"><Icon name="globe" size={36} /><h3>Map geometry unavailable</h3></div>}
        {geo && <div className="legend"><span className="num">0</span><span className="bar" style={{ background: `linear-gradient(90deg,${cssVar('--map-0')},${cssVar('--map-1')},${cssVar('--map-2')})` }} /><span className="num">{fmt(geo.max)}</span><span>{UNIT[view][0].split(',')[0]}</span></div>}
        {tip && (
          <div className="tip on" style={{ left: tip.x, top: tip.y }}>
            <div className="t"><Flag cc={tip.cc} />{countryName(tip.cc)}</div>
            <div className="n num">{fmt(t?.n ?? 0)}<span className="small muted" style={{ fontSize: 12 }}> {UNIT[view][0]}</span></div>
            {t ? <>
              {AUTHORITIES.filter(a => t.by[a]).map(a => <div key={a} className="row"><span>{ALABEL[a]}</span><span className="num">{fmt(t.by[a])}</span></div>)}
              {t.usd > 0 && <div className="row"><span>Penalties (USD)</span><span className="num">{money(t.usd, 'USD')}</span></div>}
              {t.gbp > 0 && <div className="row"><span>Penalties (GBP)</span><span className="num">{money(t.gbp, 'GBP')}</span></div>}
              <div className="row"><span>Latest</span><span className="num">{t.last}</span></div>
            </> : <div className="muted">{UNIT[view][1]}</div>}
          </div>
        )}
      </div>
    </>
  );
}

function Years({ actions }: { actions: EnforcementAction[] }) {
  const rows = useMemo(() => {
    const m = new Map<string, Record<string, number>>();
    actions.forEach(a => { const y = a.date.slice(0, 4); const r = m.get(y) ?? {}; r[a.source] = (r[a.source] ?? 0) + 1; m.set(y, r); });
    const ys = [...m.keys()].map(Number); if (!ys.length) return [];
    const out: [string, Record<string, number>][] = [];
    for (let y = Math.min(...ys); y <= Math.max(...ys); y++) out.push([String(y), m.get(String(y)) ?? {}]);
    return out;
  }, [actions]);
  if (!rows.length) return <span className="muted small">No actions in this selection.</span>;
  const max = Math.max(1, ...rows.map(([, r]) => Object.values(r).reduce((s, n) => s + n, 0)));
  const W = 100 / rows.length;
  return (
    <div>
      <svg viewBox="0 0 100 50" preserveAspectRatio="none" style={{ width: '100%', height: 180, display: 'block' }} role="img" aria-label="Actions per year by authority">
        {rows.map(([y, r], i) => { let acc = 0; return AUTHORITIES.filter(a => r[a]).map(a => { const h = (r[a] / max) * 48; acc += h; return <rect key={y + a} x={i * W + W * 0.12} width={W * 0.76} y={50 - acc} height={h} fill={ACOL[a]}><title>{`${y} · ${a}: ${r[a]}`}</title></rect>; }); })}
      </svg>
      <div style={{ display: 'flex', justifyContent: 'space-between' }} className="xs muted num"><span>{rows[0][0]}</span><span>{rows[rows.length - 1][0]}</span></div>
    </div>
  );
}

function Programs({ actions }: { actions: EnforcementAction[] }) {
  const top = useMemo(() => { const m = new Map<string, number>(); actions.forEach(a => a.programs.forEach(p => m.set(p, (m.get(p) ?? 0) + 1))); return [...m].sort((a, b) => b[1] - a[1]).slice(0, 10); }, [actions]);
  if (!top.length) return <span className="muted small">No programs named in this selection.</span>;
  return <>{top.map(([p, n]) => <HBar key={p} label={p} pct={(n / top[0][1]) * 100} value={fmt(n)} />)}</>;
}

function Actions({ actions }: { actions: EnforcementAction[] }) {
  const [n, setN] = useState(PAGE);
  useEffect(() => setN(PAGE), [actions]);
  if (!actions.length) return <div className="empty">No actions in this selection.</div>;
  return (
    <>
      <div style={{ overflowX: 'auto' }}>
        <table className="tbl">
          <thead><tr><th>Date</th><th>Authority</th><th>Party or case</th><th>Programs</th><th className="r">Amount</th><th /></tr></thead>
          <tbody>{actions.slice(0, n).map(a => (
            <tr key={a.id}>
              <td className="num" style={{ whiteSpace: 'nowrap' }}>{a.date}</td>
              <td><span className="cl"><Flag cc={a.cc} />{ALABEL[a.source]}</span><div className="xs muted">{KIND_LABEL[a.kind]}</div></td>
              <td style={{ minWidth: 220 }}>{a.party ?? a.summary}{a.party && a.summary && <div className="xs muted">{a.summary}</div>}</td>
              <td><div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>{a.programs.map(p => <Chip key={p}>{p}</Chip>)}</div></td>
              <td className="r num" style={{ whiteSpace: 'nowrap' }}>{a.amount != null ? money(a.amount, a.currency) : '—'}{a.violations && a.violations > 1 ? <div className="xs muted">{fmt(a.violations)} counts</div> : null}</td>
              <td><a href={a.url} target="_blank" rel="noopener" aria-label="Notice" className="cl"><Icon name="ext" size={14} /></a></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {n < actions.length && <div className="map-foot"><span>Showing {fmt(n)} of {fmt(actions.length)}</span><button className="btn sm" onClick={() => setN(v => v + PAGE * 4)}>Show more</button></div>}
    </>
  );
}

function News({ items }: { items: SuspectedBreach[] }) {
  const [n, setN] = useState(25);
  useEffect(() => setN(25), [items]);
  if (!items.length) return <div className="empty">No news reports in this selection.</div>;
  return (
    <>
      <div style={{ overflowX: 'auto' }}>
        <table className="tbl">
          <thead><tr><th>Seen</th><th>Headline</th><th>Programs</th><th>Site</th></tr></thead>
          <tbody>{items.slice(0, n).map(b => (
            <tr key={b.url}>
              <td className="num" style={{ whiteSpace: 'nowrap' }}>{b.seenAt.slice(0, 10)}</td>
              <td style={{ minWidth: 260 }}><a href={b.url} target="_blank" rel="noopener nofollow">{b.title}</a></td>
              <td><div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>{b.programs.map(p => <Chip key={p}>{p}</Chip>)}</div></td>
              <td className="small muted">{b.domain}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {n < items.length && <div className="map-foot"><span>Showing {fmt(n)} of {fmt(items.length)}</span><button className="btn sm" onClick={() => setN(v => v + 100)}>Show more</button></div>}
    </>
  );
}

function Reports({ items }: { items: BreachReport[] }) {
  const [n, setN] = useState(25);
  useEffect(() => setN(25), [items]);
  if (!items.length) return <div className="empty">No accepted reports in this selection.</div>;
  return (
    <>
      <div style={{ overflowX: 'auto' }}>
        <table className="tbl">
          <thead><tr><th>Accepted</th><th>Reported party</th><th>Programs</th><th>Sources</th><th /></tr></thead>
          <tbody>{items.slice(0, n).map(r => (
            <tr key={r.id}>
              <td className="num" style={{ whiteSpace: 'nowrap' }}>{r.acceptedAt}</td>
              <td style={{ minWidth: 260 }}>{r.party}<div className="xs muted">{r.summary}</div>{(r.when || r.where) && <div className="xs muted">{[r.when, r.where].filter(Boolean).join(' · ')}</div>}</td>
              <td><div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>{r.programs.map(p => <Chip key={p}>{p}</Chip>)}{r.otherProgram && <Chip>Other</Chip>}</div></td>
              <td className="small">{r.sources.map(u => <div key={u}><a href={u} target="_blank" rel="noopener nofollow ugc" className="cl">{host(u)} <Icon name="ext" size={12} /></a></div>)}</td>
              <td><a href={r.url} target="_blank" rel="noopener" className="small" style={{ whiteSpace: 'nowrap' }}>Report #{r.issue}</a></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {n < items.length && <div className="map-foot"><span>Showing {fmt(n)} of {fmt(items.length)}</span><button className="btn sm" onClick={() => setN(v => v + 100)}>Show more</button></div>}
    </>
  );
}
