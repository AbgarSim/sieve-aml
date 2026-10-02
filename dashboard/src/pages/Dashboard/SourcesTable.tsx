import { Fragment, useMemo, useState } from 'react';
import { Flag } from '../../components/Flag';
import { Chip, Delta, StatusDot } from '../../components/Badges';
import { Sparkline } from '../../components/Sparkline';
import { Icon } from '../../lib/icons';
import { SIEVE, type Source } from '../../data/snapshot';
import { ago, clock, fmt, host, ms } from '../../lib/format';

type Key = keyof Source;
const HAS_HISTORY = () => SIEVE.history.length >= 2;

export function SourcesTable() {
  const [key, setKey] = useState<Key>('entities');
  const [dir, setDir] = useState(-1);
  const [q, setQ] = useState('');
  const [open, setOpen] = useState<Set<string>>(new Set());
  const hist = HAS_HISTORY();
  const cols: [Key, string, boolean?][] = [['name', 'List'], ['authority', 'Authority'], ['format', 'Format'], ['entities', 'Entities', true], ...(hist ? [['spark', '30 days'] as [Key, string]] : []), ['status', 'Status'], ['lastFetched', 'Fetched'], ...(hist ? [['changedDays', 'Count changed'] as [Key, string]] : []), ['fetchMs', 'Fetch time', true]];
  const rows = useMemo(() => {
    const qq = q.toLowerCase();
    const r = SIEVE.sources.filter(s => !qq || `${s.name} ${s.authority} ${s.format} ${s.region}`.toLowerCase().includes(qq));
    r.sort((a, b) => { let x: unknown = a[key], y: unknown = b[key]; if (x == null) x = dir > 0 ? Infinity : -Infinity; if (y == null) y = dir > 0 ? Infinity : -Infinity; return ((x as number) > (y as number) ? 1 : (x as number) < (y as number) ? -1 : 0) * dir; });
    return r;
  }, [key, dir, q]);
  const sort = (k: Key) => { if (k === 'spark') return; if (k === key) setDir(d => -d); else { setKey(k); setDir(k === 'name' || k === 'authority' ? 1 : -1); } };
  const toggle = (id: string) => setOpen(o => { const n = new Set(o); n.has(id) ? n.delete(id) : n.add(id); return n; });
  return (
    <div className="card">
      <div className="filters">
        <div className="search" style={{ width: 260, height: 30 }}><Icon name="search" /><input value={q} onChange={e => setQ(e.target.value)} placeholder="Filter sources…" aria-label="Filter sources" /></div>
        <span className="sp" /><span className="small muted">{rows.length} of {SIEVE.sources.length} lists</span>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table className="tbl cards">
          <thead><tr><th style={{ width: 28 }} />{cols.map(([k, l, r]) => <th key={k} className={(r ? 'r' : '') + (key === k ? ' on' : '') + (dir < 0 ? ' desc' : '')}><button onClick={() => sort(k)}>{l}{k !== 'spark' && <Icon name="up" size={12} className="si" />}</button></th>)}</tr></thead>
          <tbody>
            {rows.map(s => { const o = open.has(s.id), none = !s.entities; return (
              <Fragment key={s.id}>
                <tr className="row" aria-expanded={o} tabIndex={0} onClick={() => toggle(s.id)} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(s.id); } }}>
                  <td style={{ paddingRight: 0 }}><Icon name="right" size={12} className="cv" /></td>
                  <td className="full" data-l="List"><span className="cl"><Flag cc={s.cc} /><b style={{ color: 'var(--heading)' }}>{s.name}</b></span></td>
                  <td data-l="Authority" style={{ minWidth: 220 }}><span className="small" style={{ color: 'var(--text-2)' }}>{s.authority}</span></td>
                  <td data-l="Format"><Chip>{s.format}</Chip></td>
                  <td className="r num" data-l="Entities">{none ? <span className="muted">—</span> : fmt(s.entities)}</td>
                  {hist && <td data-l="30 days"><span style={{ color: 'var(--muted)' }}>{s.spark && <Sparkline data={s.spark} w={80} h={20} />}</span></td>}
                  <td data-l="Status"><StatusDot status={s.status} /></td>
                  <td className="num" data-l="Fetched">{s.lastFetched ? clock(s.lastFetched) : <span className="muted">—</span>}</td>
                  {hist && <td className="num" data-l="Count changed">{s.changedDays == null ? <span className="muted">—</span> : ago(s.changedDays)}</td>}
                  <td className="r num" data-l="Fetch time">{s.fetchMs == null ? <span className="muted">—</span> : ms(s.fetchMs)}</td>
                </tr>
                {o && (
                  <tr className="exp"><td colSpan={cols.length + 1}>
                    <dl className="kv">
                      <div><dt>Region</dt><dd>{s.region}</dd></div>
                      <div><dt>Names (incl. aliases)</dt><dd className="num">{none ? '—' : fmt(s.names)}</dd></div>
                      {s.delta != null && <div><dt>Change vs previous</dt><dd><Delta n={s.delta} /></dd></div>}
                      <div><dt>Countries linked</dt><dd className="num">{none ? '—' : fmt(s.countries)}</dd></div>
                      <div><dt>Type split</dt><dd className="small">{none ? '—' : `Ind ${Math.round(s.types[0] * 100)}% · Ent ${Math.round(s.types[1] * 100)}% · Ves ${Math.round(s.types[2] * 100)}% · Air ${Math.round(s.types[3] * 100)}%`}</dd></div>
                      <div><dt>Largest program</dt><dd className="small">{s.topPrograms[0] ? <><span className="num">{s.topPrograms[0].code}</span> · {fmt(s.topPrograms[0].entities)}</> : '—'}</dd></div>
                      <div><dt>Publisher</dt><dd><a href={s.homepage} target="_blank" rel="noopener" className="cl" onClick={e => e.stopPropagation()}>{host(s.homepage)} <Icon name="ext" size={12} /></a></dd></div>
                      {s.listUri && <div><dt>Data file</dt><dd><a href={s.listUri} target="_blank" rel="noopener" className="cl" onClick={e => e.stopPropagation()}>{host(s.listUri)} <Icon name="ext" size={12} /></a></dd></div>}
                    </dl>
                    {s.status === 'needs-key' && <div className="callout" style={{ marginTop: 14 }}><Icon name="warn" size={18} /><span>Requires an API key from the authority. Set <span className="num">SIEVE_NSDC_API_KEY</span> for the nightly run and the list loads on the next snapshot.</span></div>}
                    {s.status === 'failed' && <div className="callout" style={{ marginTop: 14 }}><Icon name="warn" size={18} /><span>Tonight's fetch failed{s.error ? <>: <span className="num">{s.error}</span></> : '.'}</span></div>}
                  </td></tr>
                )}
              </Fragment>
            ); })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
