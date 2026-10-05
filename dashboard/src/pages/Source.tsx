import { Link, useParams } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { HBar } from '../components/HBar';
import { Chip, StatusDot, TopicBadge } from '../components/Badges';
import { Icon } from '../lib/icons';
import { FIELDS, SIEVE, TYPES, TYPE_LABEL, byTopicOrder, topicLabel, topicNoun } from '../data/snapshot';
import { clock, fmt, host, ms } from '../lib/format';

/** One list: what it is, who publishes it, what tonight's snapshot holds from it, and a way into its records. */
export default function Source() {
  const { id = '' } = useParams();
  const D = SIEVE, s = D.byId[id];
  if (!s) return <><Header active="Sources" /><main className="wrap page"><div className="card empty"><Icon name="warn" size={36} /><h3>No source {id}</h3><p><Link to="/?section=sources">All sources</Link></p></div></main><Footer /></>;
  document.title = `${s.name} — Sieve`;
  const none = !s.entities;
  const topics = [...s.topics].sort((a, b) => byTopicOrder(a[0], b[0]));
  const tmax = Math.max(1, ...topics.map(t => t[1]));
  const types = TYPES.map((t, i) => [t, s.types[i] ?? 0] as const).filter(([, v]) => v > 0);
  const Fact = ({ l, children }: { l: string; children: React.ReactNode }) => <div><dt>{l}</dt><dd>{children}</dd></div>;
  return (
    <>
      <Header active="Sources" />
      <main className="wrap page">
        <nav className="small muted" style={{ marginBottom: 14 }} aria-label="Breadcrumb"><Link to="/?section=sources">Sources</Link> <span style={{ margin: '0 6px' }}>/</span> <span>{s.name}</span></nav>
        <div className="ehd">
          <div style={{ flex: '1 1 480px', minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}><Chip>{s.format}</Chip>{topics.map(([k]) => <TopicBadge key={k} topic={k} />)}<StatusDot status={s.status} /></div>
            <h1 className="cl" style={{ gap: 10 }}><Flag cc={s.cc} />{s.name}</h1>
            <p className="summary">{s.description || `Published by ${s.authority}.`}</p>
          </div>
          <div className="acts">
            {!s.countsOnly && s.published > 0 && <Link className="btn pri" to={`/search?source=${s.id}`}><Icon name="search" />Browse {fmt(s.published)} records</Link>}
            <a className="btn" href={s.homepage} target="_blank" rel="noopener"><Icon name="ext" />Publisher</a>
          </div>
        </div>
        <div className="eprof">
          <div style={{ display: 'grid', gap: 20, minWidth: 0 }}>
            <div className="card"><div className="card-b">
              <dl className="kv">
                <Fact l="Publisher">{s.authority}</Fact>
                <Fact l="Region">{s.region}</Fact>
                <Fact l="Entities"><span className="num">{none ? '—' : fmt(s.entities)}</span></Fact>
                <Fact l="Records published"><span className="num">{none ? '—' : fmt(s.published)}</span></Fact>
                {D.distinctEntities != null && !s.countsOnly && <Fact l="Also on other lists"><span className="num">{none ? '—' : fmt(s.onOtherLists)}</span></Fact>}
                <Fact l="Names incl. aliases"><span className="num">{none ? '—' : fmt(s.names)}</span></Fact>
                <Fact l="Countries linked"><span className="num">{none ? '—' : fmt(s.countries)}</span></Fact>
                <Fact l="Fetched"><span className="num">{s.lastFetched ? `${s.lastFetched.slice(0, 10)} ${clock(s.lastFetched)}` : '—'}</span></Fact>
                <Fact l="Fetch time"><span className="num">{s.fetchMs == null ? '—' : ms(s.fetchMs)}</span></Fact>
                <Fact l="Website"><a href={s.homepage} target="_blank" rel="noopener" className="cl">{host(s.homepage)} <Icon name="ext" size={12} /></a></Fact>
                {s.listUri && <Fact l="Data file"><a href={s.listUri} target="_blank" rel="noopener" className="cl">{host(s.listUri)} <Icon name="ext" size={12} /></a></Fact>}
              </dl>
              {s.countsOnly && <div className="callout" style={{ marginTop: 16 }}><Icon name="info" size={18} /><span>{topics.map(([k]) => topicNoun(k)).join(' and ').replace(/^./, c => c.toUpperCase()) || 'These records'} are counted here, but their records are not published or searchable.</span></div>}
              {s.status === 'failed' && <div className="callout" style={{ marginTop: 16 }}><Icon name="warn" size={18} /><span>Tonight's fetch failed{s.error ? <>: <span className="num">{s.error}</span></> : '.'}</span></div>}
            </div></div>
            {s.topPrograms.length > 0 && (
              <div className="card">
                <div className="card-h"><h3>Largest programs</h3></div>
                <div className="card-b">{s.topPrograms.map(p => <HBar key={p.code} label={<span title={p.name} className="num">{p.code}{p.name && p.name !== p.code && <span className="muted" style={{ fontFamily: 'Inter, sans-serif' }}> · {p.name}</span>}</span>} pct={(p.entities / s.topPrograms[0].entities) * 100} value={fmt(p.entities)} />)}</div>
              </div>
            )}
          </div>
          <aside className="eside">
            {topics.length > 0 && <div className="card"><div className="card-h"><h3>Risk topics</h3></div><div className="card-b">{topics.map(([k, n]) => <HBar key={k} label={s.countsOnly ? topicLabel(k) : <Link to={`/search?source=${s.id}&topic=${k}`}>{topicLabel(k)}</Link>} pct={(n / tmax) * 100} value={fmt(n)} />)}</div></div>}
            {!none && types.length > 0 && <div className="card"><div className="card-h"><h3>Entity types</h3></div><div className="card-b">{types.map(([t, v]) => <HBar key={t} label={TYPE_LABEL[t]} pct={v * 100} value={`${Math.round(v * 100)}%`} />)}</div></div>}
            {!none && <div className="card"><div className="card-h"><h3>Completeness</h3><span className="xs muted">share of records with</span></div><div className="card-b">{FIELDS.map((f, i) => <HBar key={f} label={f} pct={s.completeness[i]} value={`${s.completeness[i]}%`} accent />)}</div></div>}
          </aside>
        </div>
      </main>
      <Footer />
    </>
  );
}
