import type { ReactNode } from 'react';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { StatTile } from '../components/StatTile';
import { Badge, Chip, Delta, StatusDot, TypeBadge } from '../components/Badges';
import { Flag } from '../components/Flag';
import { HBar } from '../components/HBar';
import { LineChart } from '../components/LineChart';
import { Icon } from '../lib/icons';
import { BENCH } from '../data/benchmarks';
import { fmt } from '../lib/format';

const Pane = ({ children }: { children: ReactNode }) => (
  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(360px,1fr))', gap: 16, marginBottom: 28 }}>
    {(['dark', 'light'] as const).map(t => <div key={t} data-theme={t} style={{ padding: 20, borderRadius: 12, border: '1px solid var(--border)', background: 'var(--bg)', color: 'var(--text)', minWidth: 0 }}><div className="num xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 12 }}>{t}</div>{children}</div>)}
  </div>
);
const H = ({ t, s }: { t: string; s: string }) => <h2 style={{ margin: '0 0 10px' }}>{t}<span style={{ fontSize: 12, color: 'var(--muted)', fontWeight: 400, marginLeft: 8 }}>{s}</span></h2>;
const row: React.CSSProperties = { display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' };

export default function ComponentSheet() {
  // Illustrative values only; this page shows how components look, not data
  const s = { cc: 'us', name: 'OFAC SDN', format: 'XML', entities: 18210 };
  const uk = { cc: 'gb', name: 'UK HMT', format: 'XML', entities: 4036, authority: 'HM Treasury, Office of Financial Sanctions Implementation', homepage: 'https://www.gov.uk/' };
  const ua = { cc: 'ua', name: 'UA NSDC', format: 'JSON' };
  const spark = [17980, 17990, 18010, 18040, 18040, 18090, 18120, 18150, 18190, 18210];
  return (
    <>
      <Header />
      <main className="wrap page">
        <div className="blk-h"><h1>Component sheet</h1><p>Every component rendered in dark and light, with illustrative values. Flat cards, 1px borders, no shadows; teal is the single accent, red/amber/green are status only.</p></div>
        <H t="Stat tile" s="label · tabular number · delta · sparkline" />
        <Pane><div className="grid" style={{ gridTemplateColumns: '1fr 1fr' }}><StatTile label="Entities" value={fmt(48210)} delta={412} spark={spark} /><StatTile label="Sources online" value={<>24<small>/25</small></>} delta={0} spark={[23, 24, 24, 24, 22, 24, 24, 24, 24, 24]} /></div></Pane>
        <H t="Status dot" s="Loaded · Empty · Failed · Needs key" />
        <Pane><div style={row}><StatusDot status="loaded" /><StatusDot status="empty" /><StatusDot status="failed" /><StatusDot status="needs-key" /></div></Pane>
        <H t="Format chip" s="mono, one per source" />
        <Pane><div style={row}>{['XML', 'JSON', 'XLSX', 'CSV', 'HTML'].map(f => <Chip key={f}>{f}</Chip>)}<Chip accent>RUSSIA-EO14024</Chip></div></Pane>
        <H t="Source badge" s="flag + list name; accent variant for membership" />
        <Pane><div style={row}><Badge cc="us">OFAC SDN</Badge><Badge cc="eu">EU Consolidated</Badge><Badge cc="un">UN Consolidated</Badge><Badge variant="acc" cc="gb">UK HMT</Badge><Badge variant="acc" cc="ch">CH SECO</Badge><TypeBadge type="individual" /><TypeBadge type="vessel" /><Badge variant="red"><Icon name="shield" size={12} />Sanctioned</Badge></div></Pane>
        <H t="Facet checkbox list" s="count right-aligned; selected first" />
        <Pane><div className="facet" style={{ maxWidth: 240, margin: 0 }}><h4>Entity type <button className="xs" style={{ color: 'var(--link)', textTransform: 'none', letterSpacing: 0, marginLeft: 6 }}>clear</button></h4>
          {[['user', 'Individual', 21, true], ['building', 'Entity', 9, false], ['cube', 'Vessel', 2, false], ['plane', 'Aircraft', 1, false]].map(([i, l, n, c]) => <label key={l as string}><input type="checkbox" defaultChecked={c as boolean} /><span className="cl" style={{ flex: 1 }}><Icon name={i as 'user'} size={13} style={{ color: 'var(--muted)' }} /> {l as string}</span><span className="cnt num">{n as number}</span></label>)}
          <button className="more">Show all 4</button></div></Pane>
        <H t="Tooltip card" s="map hover" />
        <Pane><div className="tip on" style={{ position: 'static', display: 'inline-block' }}><div className="t"><Flag cc="ru" />Russia</div><div className="n num">14,820<span className="small muted" style={{ fontSize: 12 }}> entities</span></div><div className="xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.04em' }}>Top sources</div>{[['OFAC SDN', '3,412'], ['EU Consolidated', '2,980'], ['UK HMT', '2,104']].map(([a, b]) => <div key={a} className="row"><span>{a}</span><span className="num">{b}</span></div>)}<div className="xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.04em', marginTop: 6 }}>Type split</div><div className="bar"><i style={{ width: '58%', background: 'var(--map-1)' }} /><i style={{ width: '33%', background: 'var(--accent)' }} /><i style={{ width: '6%', background: 'var(--amber)' }} /><i style={{ width: '3%', background: 'var(--red)' }} /></div><div className="row xs"><span>Ind. 58%</span><span>Ent. 33%</span><span>Ves. 6%</span><span>Air. 3%</span></div></div></Pane>
        <H t="Slide-over panel" s="country detail, docked right" />
        <Pane><div className="so on" style={{ position: 'relative', width: '100%', maxWidth: 400, transform: 'none', height: 'auto', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
          <div className="so-h"><h2><Flag cc="ru" />Russia</h2><Chip>RU</Chip><button className="ibtn" style={{ marginLeft: 'auto' }} aria-label="Close"><Icon name="x" size={18} /></button></div>
          <div className="so-b"><div className="so-sec" style={{ display: 'grid', gridTemplateColumns: 'repeat(2,1fr)', gap: 12 }}><div className="card kpi"><div className="l">Entities</div><div className="v num" style={{ fontSize: 22 }}>14,820</div></div><div className="card kpi"><div className="l">Share of total</div><div className="v num" style={{ fontSize: 22 }}>18.0%</div></div></div>
            <div className="so-sec"><h4>Per source</h4><HBar label={<><Flag cc="us" />OFAC SDN</>} pct={100} value="3,412" /><HBar label={<><Flag cc="eu" />EU Consolidated</>} pct={87} value="2,980" /><HBar label={<><Flag cc="gb" />UK HMT</>} pct={62} value="2,104" /></div>
            <div className="so-sec"><h4>Entity types</h4><HBar label="Individual" pct={100} value="8,410" /><HBar label="Entity" pct={68} value="5,720" /></div></div>
          <div className="so-f"><button className="btn pri">View entities <Icon name="right" /></button><button className="btn">Close</button></div></div></Pane>
        <H t="Table row" s="collapsed and expanded" />
        <Pane><div className="card" style={{ overflowX: 'auto' }}><table className="tbl"><thead><tr><th /><th>List</th><th>Format</th><th className="r">Entities</th><th>Status</th><th>Last fetched</th></tr></thead><tbody>
          <tr className="row" aria-expanded={false}><td style={{ paddingRight: 0 }}><Icon name="right" size={12} className="cv" /></td><td><span className="cl"><Flag cc={s.cc} /><b style={{ color: 'var(--heading)' }}>{s.name}</b></span></td><td><Chip>{s.format}</Chip></td><td className="r num">{fmt(s.entities)}</td><td><StatusDot status="loaded" /></td><td className="num">03:00:12</td></tr>
          <tr className="row" aria-expanded={true}><td style={{ paddingRight: 0 }}><Icon name="right" size={12} className="cv" /></td><td><span className="cl"><Flag cc={uk.cc} /><b style={{ color: 'var(--heading)' }}>{uk.name}</b></span></td><td><Chip>{uk.format}</Chip></td><td className="r num">{fmt(uk.entities)}</td><td><StatusDot status="loaded" /></td><td className="num">03:00:07</td></tr>
          <tr className="exp"><td colSpan={6}><dl className="kv"><div><dt>Authority</dt><dd>{uk.authority}</dd></div><div><dt>Change vs previous</dt><dd><Delta n={18} /></dd></div><div><dt>Publisher</dt><dd><a href={uk.homepage} className="cl">gov.uk <Icon name="ext" size={12} /></a></dd></div></dl></td></tr>
          <tr className="row" aria-expanded={false}><td style={{ paddingRight: 0 }}><Icon name="right" size={12} className="cv" /></td><td><span className="cl"><Flag cc={ua.cc} /><b style={{ color: 'var(--heading)' }}>{ua.name}</b></span></td><td><Chip>{ua.format}</Chip></td><td className="r num muted">—</td><td><StatusDot status="needs-key" /></td><td className="num muted">—</td></tr>
        </tbody></table></div></Pane>
        <H t="Chart card" s="title · unit · plot · environment footnote" />
        <Pane><div className="card"><div className="card-h"><h3>Concurrency scaling</h3><span className="xs muted">req/s</span></div><div className="card-b"><LineChart xLog series={[{ pts: BENCH.rampUp.map(r => [r[0], r[1]]), color: 'var(--map-1)' }]} yLabel="req/s" xLabel="concurrency" xFmt={String} /></div><div className="card-f">{BENCH.env}</div></div></Pane>
        <H t="Controls" s="segmented · tabs · buttons · select · search" />
        <Pane>
          <div style={{ ...row, marginBottom: 12 }}><div className="seg"><button aria-pressed={false}>Nationality</button><button aria-pressed={false}>Address</button><button aria-pressed={true}>Both</button></div><button className="btn"><Icon name="download" />Download JSON</button><button className="btn pri">View entities <Icon name="right" /></button><button className="btn sm">Sources · all 24 <Icon name="down" size={12} /></button><select className="sel" defaultValue="all"><option value="all">All types</option></select></div>
          <div style={row}><div className="tabs" style={{ border: 0 }}><button aria-selected={true}>Targets</button><button aria-selected={false}>Authorities</button><button aria-selected={false}>Flows</button></div><div className="search" style={{ width: 240 }}><Icon name="search" /><input placeholder="Screen a name…" /><span className="kbd">/</span></div><span className="pill"><span className="d" />Snapshot · 2026-10-02 03:04 UTC</span><span className="pill warn"><span className="d" />Sample data</span></div>
        </Pane>
        <H t="States" s="skeleton · empty · no results" />
        <Pane>
          <div className="card" style={{ marginBottom: 10 }}><div className="res"><div className="skl" style={{ width: 34, height: 34, flex: 'none' }} /><div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}><div className="skl" style={{ height: 14, width: '45%' }} /><div className="skl" style={{ height: 11, width: '30%' }} /></div></div></div>
          <div className="card"><div className="empty" style={{ padding: 28 }}><Icon name="funnel" size={36} /><h3>No matches for “Dubrovnik”</h3><p>Lower the threshold or check the spelling.</p></div></div>
        </Pane>
      </main>
      <Footer />
    </>
  );
}
