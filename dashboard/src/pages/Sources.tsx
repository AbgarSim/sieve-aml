import { useEffect } from 'react';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { StatTile } from '../components/StatTile';
import { IngestChart } from '../components/IngestChart';
import { SourcesTable } from './Dashboard/SourcesTable';
import { SIEVE } from '../data/snapshot';
import { fmt, ms } from '../lib/format';

/** Every list Sieve reads: what loaded tonight, with each list's numbers, and how long each fetch took. */
export default function Sources() {
  const D = SIEVE, S = D.snapshot;
  useEffect(() => { document.title = 'Sources — Sieve'; }, []);
  const failed = D.sources.filter(s => s.status === 'failed').length;
  const waiting = D.sources.filter(s => s.status === 'needs-key').length;
  const published = D.sources.reduce((a, s) => a + s.published, 0);
  const authorities = new Set(D.sources.map(s => s.authority)).size;
  return (
    <>
      <Header active="Sources" />
      <main className="wrap page">
        <section className="blk">
          <div className="blk-h"><h1>Sources</h1><p>{D.sources.length} official lists from {authorities} authorities, fetched every night. Click a row for fetch details and the publisher's link; click a list's name for its page.</p></div>
          <div className="grid g4" style={{ marginBottom: 16 }}>
            <StatTile label="Lists online" value={<>{S.sourcesLoaded}<small>/{S.sourcesTotal}</small></>} note={failed ? `${failed} failed tonight` : waiting ? `${waiting} waiting for an API key` : 'all fetches succeeded'} />
            <StatTile label="Records published" value={fmt(published)} note={D.unpublished > 0 ? `plus ${fmt(D.unpublished)} counted only` : 'searchable, with a profile each'} />
            <StatTile label="Distinct entities" value={D.distinctEntities == null ? '—' : fmt(D.distinctEntities)} note={D.onSeveralLists ? `${fmt(D.onSeveralLists)} on several lists` : 'matched across lists'} />
            <StatTile label="Longest fetch" value={S.ingestMs == null ? '—' : ms(S.ingestMs)} note="lists are fetched in parallel" />
          </div>
          <SourcesTable />
        </section>
        <section className="blk" id="ingest">
          <div className="blk-h"><h2>Tonight's fetch</h2><p>Time to fetch and parse each list. Lists are fetched in parallel, so the longest one sets the ingest time.</p></div>
          <div className="card"><div className="card-b"><IngestChart /></div><div className="card-f">Measured by the nightly snapshot run · {S.date}</div></div>
        </section>
      </main>
      <Footer />
    </>
  );
}
