import { useEffect, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { Header } from '../../components/Header';
import { Footer } from '../../components/Footer';
import { Kpis } from './Kpis';
import { WorldMap } from './WorldMap';
import { Composition } from './Composition';
import { Quality } from './Quality';
import { Benchmarks } from './Benchmarks';
import { SIEVE } from '../../data/snapshot';
import { useChanges, nightsOf } from '../../data/changes';
import { Icon } from '../../lib/icons';
import { fmt } from '../../lib/format';

const SUBNAV: [string, string][] = [['Overview', 'overview'], ['Map', 'map'], ['Composition', 'composition'], ['Quality', 'quality'], ['Benchmarks', 'benchmarks']];

/** Tonight's new listings, delistings and changed records, with the way to the full log. */
function Tonight() {
  const { changes } = useChanges();
  const night = nightsOf(changes)[0];
  if (!night) return null;
  return (
    <div className="tonight">
      <span className="small muted">Changes on {night.date}:</span>
      <span className="num"><b className="pos">+{fmt(night.added)}</b> new</span>
      <span className="num"><b className="neg">−{fmt(night.removed)}</b> delisted</span>
      <span className="num"><b>{fmt(night.changed)}</b> changed</span>
      <Link to="/changes" className="cl">What changed tonight <Icon name="right" size={12} /></Link>
    </div>
  );
}

function useActiveSection() {
  const [cur, setCur] = useState('overview');
  useEffect(() => {
    const upd = () => {
      const tops = SUBNAV.map(s => document.getElementById(s[1])).filter(Boolean).map(el => [el!.id, el!.getBoundingClientRect().top] as const).filter(x => x[1] <= 140);
      setCur(tops.length ? tops[tops.length - 1][0] : 'overview');
    };
    upd(); window.addEventListener('scroll', upd, { passive: true }); return () => window.removeEventListener('scroll', upd);
  }, []);
  return cur;
}

export default function Dashboard() {
  const active = useActiveSection();
  const [params] = useSearchParams();
  const section = params.get('section');
  useEffect(() => { if (section) document.getElementById(section)?.scrollIntoView(); }, [section]);
  const S = SIEVE.snapshot;
  if (section === 'sources') return <Navigate to="/sources" replace />;
  return (
    <>
      <Header active="Overview" subnav={SUBNAV} activeSection={active} />
      <main className="wrap page">
        <section className="blk" id="overview">
          {S.sample && <div className="callout" style={{ marginBottom: 16, borderColor: 'var(--amber)' }}><Icon name="warn" size={18} /><span><b>Sample data.</b> Every name and number on this page is fictional, for local development. The published site shows the nightly snapshot.</span></div>}
          <div className="blk-h"><h1>Overview</h1><p>Rebuilt nightly from {SIEVE.sources.length} official lists</p></div>
          <Kpis />
          <Tonight />
        </section>
        <section className="blk" id="map">
          <div className="blk-h"><h2>Sanctioned entities by country</h2><p>Entities linked to each country by nationality or address. Hover for detail, click a country to open it.</p></div>
          <WorldMap />
        </section>
        <section className="blk" id="composition">
          <div className="blk-h"><h2>Composition</h2><p>Entities per source, entity type mix, risk topics and the largest programs.</p></div>
          <Composition />
        </section>
        <section className="blk" id="quality">
          <div className="blk-h"><h2>Data quality</h2><p>Share of entities per source carrying each optional field, name scripts, identifiers and unresolved countries.</p></div>
          <Quality />
        </section>
        <section className="blk" id="benchmarks">
          <div className="blk-h"><h2>Benchmarks</h2><p>Screening performance from <span className="num">sieve-benchmark</span> against OFAC SDN (~20k entities, ~100k names). Tonight's fetch times are on the <Link to="/sources">Sources</Link> page.</p></div>
          <Benchmarks />
        </section>
      </main>
      <Footer />
    </>
  );
}
