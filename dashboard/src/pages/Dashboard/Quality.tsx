import { useMemo } from 'react';
import * as d3 from 'd3';
import { Flag } from '../../components/Flag';
import { HBar } from '../../components/HBar';
import { FIELDS, SIEVE } from '../../data/snapshot';
import { cssVar, fmt } from '../../lib/format';
import { useTheme } from '../../lib/theme';

export const useRamp = () => { const { theme } = useTheme(); return useMemo(() => d3.interpolateRgbBasis([cssVar('--map-0'), cssVar('--map-1'), cssVar('--map-2')]), [theme]); };
const cls = (v: number) => (v > 55 ? 'hi' : v > 30 ? 'mid' : 'lo');
const label = (k: string) => k.charAt(0) + k.slice(1).toLowerCase().replace(/_/g, ' ');

function Bars({ title, sub, rows, name = label }: { title: string; sub: string; rows: [string, number][]; name?: (k: string) => string }) {
  const max = rows[0]?.[1] || 1;
  return (
    <div className="card">
      <div className="card-h"><h3>{title}</h3><span className="small muted">{sub}</span></div>
      <div className="card-b">{rows.length ? rows.map(([k, n]) => <HBar key={k} label={name(k)} pct={(n / max) * 100} value={fmt(n)} />) : <span className="muted small">None in this snapshot.</span>}</div>
    </div>
  );
}

export function Quality() {
  const D = SIEVE, rp = useRamp();
  const srt = D.sources.filter(s => s.entities).sort((a, b) => b.entities - a.entities);
  const ids = [...D.identifiersByType.filter(r => r[0] !== 'OTHER'), ...D.identifiersByType.filter(r => r[0] === 'OTHER')];
  const u = D.unresolved;
  return (
    <div className="grid g2-1">
      <div className="card">
        <div className="card-h"><h3>Field completeness</h3><span className="small muted">% of entities with field</span></div>
        <div className="card-b hm">
          <table>
            <thead><tr><th />{FIELDS.map(f => <th key={f} className="c">{f}</th>)}</tr></thead>
            <tbody>{srt.map(s => <tr key={s.id}><th><span className="cl"><Flag cc={s.cc} />{s.name}</span></th>{s.completeness.map((v, i) => <td key={i} style={{ background: rp(v / 100) }} className={cls(v)}>{v ? v + '%' : '—'}</td>)}</tr>)}</tbody>
          </table>
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr)', gap: 16, alignContent: 'start', minWidth: 0 }}>
        <Bars title="Names by script" sub="primary names and aliases" rows={D.namesByScript} name={k => (k === 'CJK' ? 'CJK' : label(k))} />
        <Bars title="Identifiers by type" sub="passports, IMO, LEI…" rows={ids} name={k => ({ IMO_NUMBER: 'IMO number', SWIFT_BIC: 'SWIFT/BIC', LEI: 'LEI', MMSI: 'MMSI' })[k] ?? label(k)} />
        <div className="card">
          <div className="card-h"><h3>Unresolved countries</h3><span className="small muted num">{fmt(u.occurrences)} values</span></div>
          <div className="card-b">
            <p className="small muted" style={{ marginBottom: 10 }}>Country text that did not map to an ISO code, so it is left off the map.</p>
            {u.top.slice(0, 8).map(([v, n]) => <div key={v} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '4px 0', fontSize: 13, borderBottom: '1px solid var(--border)' }}><span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v}</span><span className="num muted">{fmt(n)}</span></div>)}
            {!u.top.length && <span className="muted small">Every country value resolved.</span>}
          </div>
        </div>
      </div>
    </div>
  );
}
