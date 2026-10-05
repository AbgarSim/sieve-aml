import { Link } from 'react-router-dom';
import { HBar } from '../../components/HBar';
import { Flag } from '../../components/Flag';
import { Badge } from '../../components/Badges';
import { Icon, TYPE_ICON } from '../../lib/icons';
import { SIEVE, TYPE_LABEL, TYPES, topicLabel, topicNoun, type EntityType } from '../../data/snapshot';
import { fmt } from '../../lib/format';

export const TCOL: Record<EntityType, string> = {
  individual: 'var(--map-1)', entity: 'var(--accent)', company: '#3A9E9F', organization: '#8E72CF',
  vessel: 'var(--amber)', aircraft: 'var(--red)', wallet: 'var(--green)', security: 'var(--muted)',
};

export function Composition() {
  const D = SIEVE;
  const srt = D.sources.filter(s => !s.countsOnly).sort((a, b) => b.entities - a.entities), smax = srt[0]?.entities || 1;
  const counted = D.sources.filter(s => s.countsOnly);
  const tot = D.byType, tsum = TYPES.reduce((a, t) => a + tot[t], 0) || 1;
  // Kinds no list uses yet stay out of the mix; individuals and entities always show.
  const shown = TYPES.filter(t => tot[t] > 0 || t === 'individual' || t === 'entity');
  const topics = D.byTopic, tmax = topics[0]?.[1] || 1;
  const progs = D.programs.slice(0, 8);
  return (
    <div className="grid g2-1">
      <div className="card">
        <div className="card-h"><h3>Entities by source</h3><span className="small muted num">{fmt(D.totalEntities)} total</span></div>
        <div className="card-b">
          {srt.map(s => <HBar key={s.id} label={<><Flag cc={s.cc} /><Link to={`/source/${s.id}`} style={{ color: 'inherit' }}>{s.name}</Link></>} pct={(s.entities / smax) * 100} value={s.entities ? fmt(s.entities) : <span className="muted">{s.status === 'needs-key' ? 'key' : '—'}</span>} />)}
          {counted.map(s => <p key={s.id} className="small muted" style={{ marginTop: 12 }}><Flag cc={s.cc} /> {s.name}: {s.topics.length ? s.topics.map(([k, n], i) => <span key={k}>{i > 0 && ' and '}<span className="num">{fmt(n)}</span> {topicNoun(k)}</span>) : <><span className="num">{fmt(s.entities)}</span> records</>}, counted but not published.</p>)}
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr)', gap: 16, alignContent: 'start', minWidth: 0 }}>
        <div className="card">
          <div className="card-h"><h3>Entity type mix</h3></div>
          <div className="card-b">
            <div style={{ display: 'flex', height: 10, borderRadius: 5, overflow: 'hidden', gap: 2, marginBottom: 14 }}>{shown.map(t => <i key={t} style={{ width: `${(tot[t] / tsum) * 100}%`, background: TCOL[t] }} />)}</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2,minmax(0,1fr))', gap: '8px 16px' }}>
              {shown.map(t => (
                <div key={t} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
                  <i style={{ width: 8, height: 8, borderRadius: 2, background: TCOL[t] }} /><Icon name={TYPE_ICON[t]} size={14} style={{ color: 'var(--muted)' }} />
                  <span style={{ flex: 1 }}>{TYPE_LABEL[t]}</span><span className="num">{fmt(tot[t])}</span><span className="num muted xs" style={{ width: 38, textAlign: 'right' }}>{((tot[t] / tsum) * 100).toFixed(1)}%</span>
                </div>
              ))}
            </div>
          </div>
        </div>
        <div className="card">
          <div className="card-h"><h3>Risk topics</h3><span className="small muted">entities per topic, all lists</span></div>
          <div className="card-b">
            {topics.map(([k, n]) => <HBar key={k} label={k === 'PEP' || k === 'RCA' ? topicLabel(k) : <Link to={`/search?topic=${k}`}>{topicLabel(k)}</Link>} pct={(n / tmax) * 100} value={fmt(n)} />)}
            {!topics.length && <span className="muted small">No topic data in this snapshot.</span>}
          </div>
        </div>
        <div className="card">
          <div className="card-h"><h3>Largest programs</h3><span className="small muted">{fmt(D.distinctPrograms)} distinct</span></div>
          <div className="card-b" style={{ paddingTop: 8 }}>
            {progs.map((p, i) => (
              <div key={p.source + p.code} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', borderBottom: i < progs.length - 1 ? '1px solid var(--border)' : 0, fontSize: 13 }}>
                <span className="num muted xs" style={{ width: 14 }}>{i + 1}</span>
                <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={p.name}><span className="num">{p.code}</span>{p.name && p.name !== p.code && <span className="muted"> · {p.name}</span>}</span>
                <Badge cc={D.byId[p.source]?.cc}>{D.byId[p.source]?.name ?? p.source}</Badge><span className="num" style={{ minWidth: 48, textAlign: 'right' }}>{fmt(p.entities)}</span>
              </div>
            ))}
            {!progs.length && <span className="muted small">No program data in this snapshot.</span>}
          </div>
        </div>
      </div>
    </div>
  );
}
