import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { StatTile } from '../components/StatTile';
import { ChangeList } from '../components/ChangeRows';
import { Icon } from '../lib/icons';
import { SIEVE } from '../data/snapshot';
import { KINDS, KIND_INDEX, KIND_LABEL, nightsOf, sourceOf, useChanges, type ChangeKind, type Night } from '../data/changes';
import { fmt } from '../lib/format';

const PAGE = 50;

/** What changed on the lists each night: new listings, delistings and changed records, from changes.json. */
export default function Changes() {
  const { changes, error } = useChanges();
  useEffect(() => { document.title = 'What changed tonight — Sieve'; }, []);
  const nights = useMemo(() => nightsOf(changes), [changes]);
  return (
    <>
      <Header active="Changes" />
      <main className="wrap page">
        <section className="blk">
          <div className="blk-h"><h1>What changed tonight</h1><p>New listings, delistings and changed records, night by night, as Sieve reads the lists{nights.length ? ` · last ${nights.length} night${nights.length === 1 ? '' : 's'}` : ''}</p></div>
          {changes === undefined && !error && <div className="card empty"><div className="skl" style={{ width: 160, height: 12, margin: '0 auto' }} /></div>}
          {error && <div className="card empty"><Icon name="warn" size={36} /><h3>Change log unavailable</h3>{error}</div>}
          {changes === null && <div className="card empty"><Icon name="info" size={36} /><h3>No change log yet</h3>The nightly snapshot starts it on its next run; from then on every night's new listings, delistings and changes are kept here for 30 nights.</div>}
          {changes && !nights.length && <div className="card empty"><Icon name="info" size={36} /><h3>No nights recorded yet</h3></div>}
          {changes && nights.length > 0 && <Body nights={nights} sample={!!changes.sample} />}
        </section>
      </main>
      <Footer />
    </>
  );
}

function Body({ nights, sample }: { nights: Night[]; sample: boolean }) {
  const [params, setParams] = useSearchParams();
  const date = params.get('night');
  const night = nights.find(n => n.date === date) ?? nights[0];
  const source = params.get('source');
  const set = (k: string, v: string | null) => setParams(p => { const n = new URLSearchParams(p); v ? n.set(k, v) : n.delete(k); return n; }, { replace: true });
  const [shown, setShown] = useState<Record<ChangeKind, number>>({ added: PAGE, removed: PAGE, changed: PAGE });
  useEffect(() => setShown({ added: PAGE, removed: PAGE, changed: PAGE }), [night.date, source]);

  const at = nights.indexOf(night);
  const bySource = Object.entries(night.bySource ?? {}).map(([id, c]) => ({ id, src: SIEVE.byId[id], c, total: c[0] + c[1] + c[2] })).sort((a, b) => b.total - a.total || a.id.localeCompare(b.id));
  const rows = (k: ChangeKind) => (night.records?.[k] ?? []).filter(r => !source || sourceOf(r.k) === source);
  const total = (k: ChangeKind) => (source ? night.bySource?.[source]?.[KIND_INDEX[k]] ?? 0 : night[k]);
  const nothing = night.added + night.removed + night.changed === 0;
  const D = SIEVE;

  return (
    <>
      {sample && <div className="callout" style={{ marginBottom: 16, borderColor: 'var(--amber)' }}><Icon name="warn" size={18} /><span><b>Sample data.</b> Every name and number here is fictional.</span></div>}
      <div className="card" style={{ marginBottom: 20 }}>
        <div className="card-h">
          <h3 className="cl">
            <button className="ibtn" style={{ width: 28, height: 28 }} aria-label="Earlier night" disabled={at >= nights.length - 1} onClick={() => set('night', nights[at + 1].date)}><Icon name="down" size={14} style={{ transform: 'rotate(90deg)' }} /></button>
            <span className="num" style={{ fontSize: 15 }}>{night.date}</span>
            <button className="ibtn" style={{ width: 28, height: 28 }} aria-label="Later night" disabled={at <= 0} onClick={() => set('night', nights[at - 1].date)}><Icon name="right" size={14} /></button>
            {at === 0 && <span className="xs muted">latest night</span>}
          </h3>
          <span className="xs muted cl" style={{ gap: 12 }}><span className="cl" style={{ gap: 5 }}><i className="sw a" />new</span><span className="cl" style={{ gap: 5 }}><i className="sw r" />delisted</span><span className="cl" style={{ gap: 5 }}><i className="sw c" />changed</span></span>
        </div>
        <NightBars nights={nights} selected={night} onPick={n => set('night', n.date)} />
      </div>

      <div className="grid g4" style={{ marginBottom: 20 }}>
        <StatTile label="New listings" value={<span className={night.added ? 'pos' : ''}>+{fmt(night.added)}</span>} note={`records first seen on ${night.date}`} />
        <StatTile label="Delisted" value={<span className={night.removed ? 'neg' : ''}>−{fmt(night.removed)}</span>} note="records no longer on a list that loaded" />
        <StatTile label="Changed" value={fmt(night.changed)} note="records whose content changed" />
        <StatTile label="Lists with changes" value={fmt(bySource.length)} note={`of ${D.sources.length} lists`} />
      </div>

      {nothing ? (
        <div className="card empty"><Icon name="check" size={36} /><h3>Nothing changed on {night.date}</h3>Every list that loaded published the same records as the night before.</div>
      ) : (
        <>
          <div className="card" style={{ marginBottom: 20 }}>
            <div className="card-h"><h3>By list</h3>{source ? <button className="btn sm" onClick={() => set('source', null)}>All lists</button> : <span className="xs muted">click a list to show only its records</span>}</div>
            <div style={{ overflowX: 'auto' }}>
              <table className="tbl">
                <thead><tr><th>List</th><th className="r">New</th><th className="r">Delisted</th><th className="r">Changed</th></tr></thead>
                <tbody>{bySource.map(({ id, src, c }) => (
                  <tr key={id} className={'row' + (source === id ? ' on' : '')} onClick={() => set('source', source === id ? null : id)} tabIndex={0} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); set('source', source === id ? null : id); } }} aria-pressed={source === id}>
                    <td><span className="cl"><Flag cc={src?.cc} /><span style={{ color: 'var(--heading)', fontWeight: source === id ? 600 : 500 }}>{src?.name ?? id}</span>{src && <Link to={`/source/${src.id}`} className="xs" onClick={e => e.stopPropagation()}>page</Link>}</span></td>
                    <td className="r num">{c[0] ? <span className="pos">+{fmt(c[0])}</span> : <span className="muted">—</span>}</td>
                    <td className="r num">{c[1] ? <span className="neg">−{fmt(c[1])}</span> : <span className="muted">—</span>}</td>
                    <td className="r num">{c[2] ? fmt(c[2]) : <span className="muted">—</span>}</td>
                  </tr>))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="grid g3 chg-grid">
            {KINDS.map(k => (
              <div className="card" key={k}>
                <div className="card-h"><h3>{KIND_LABEL[k]}{source && <span className="muted" style={{ fontWeight: 400 }}> · {D.byId[source]?.name ?? source}</span>}</h3><span className="xs muted num">{fmt(total(k))}</span></div>
                <ChangeList rows={rows(k)} total={total(k)} link={k !== 'removed'} shown={shown[k]} onMore={() => setShown(s => ({ ...s, [k]: s[k] + PAGE }))} />
              </div>
            ))}
          </div>
          <p className="xs muted" style={{ marginTop: 16 }}>A record is new when Sieve had not seen its key before, delisted when its list loaded tonight without it, and changed when what its list states differs from the night before; the list's own last-updated stamp is not compared, because several lists set it to the fetch time. A list that failed to load counts as unchanged. Names of delisted records open no profile, because the record is gone from the snapshot.</p>
        </>
      )}
    </>
  );
}

/** One bar per night, oldest on the left, stacked by kind; each bar picks its night. */
function NightBars({ nights, selected, onPick }: { nights: Night[]; selected: Night; onPick: (n: Night) => void }) {
  const asc = [...nights].reverse();
  const max = Math.max(1, ...asc.map(n => n.added + n.removed + n.changed));
  const H = 96;
  return (
    <div className="card-b" style={{ paddingTop: 12, paddingBottom: 10 }}>
      <div className="nights" style={{ height: H }}>
        {asc.map(n => { const tot = n.added + n.removed + n.changed, px = (v: number) => Math.round((v / max) * (H - 8)); return (
          <button key={n.date} className={n === selected ? 'on' : ''} onClick={() => onPick(n)} title={`${n.date}: +${fmt(n.added)} new, −${fmt(n.removed)} delisted, ${fmt(n.changed)} changed`} aria-label={`${n.date}: ${fmt(tot)} changes`} aria-pressed={n === selected}>
            {tot === 0 ? <i className="z" /> : <><i className="c" style={{ height: px(n.changed) }} /><i className="r" style={{ height: px(n.removed) }} /><i className="a" style={{ height: px(n.added) }} /></>}
          </button>
        ); })}
      </div>
      <div className="nights-x num"><span>{asc[0].date}</span><span>{asc[asc.length - 1].date}</span></div>
    </div>
  );
}
