import { StatTile } from '../../components/StatTile';
import { SIEVE } from '../../data/snapshot';
import { fmt, ms } from '../../lib/format';

/** Last 30 values of a history column and the change since the previous snapshot, once there are two days. */
function trend(get: (r: (typeof SIEVE.history)[number]) => number) {
  const rows = SIEVE.history.slice(-30);
  if (rows.length < 2) return { spark: undefined, delta: undefined };
  const v = rows.map(get);
  return { spark: v, delta: v[v.length - 1] - v[v.length - 2] };
}

export function Kpis() {
  const D = SIEVE, S = D.snapshot;
  const first = 'First snapshot';
  const e = trend(r => r.totalEntities), n = trend(r => r.totalNames), c = trend(r => r.countries);
  const failed = D.sources.filter(s => s.status === 'failed').length;
  const pep = D.unpublished > 0;
  return (
    <div className={'grid ' + (pep ? 'g7' : 'g6')}>
      <StatTile label="Entities" value={fmt(D.totalEntities)} delta={e.delta ?? null} spark={e.spark} note={first} />
      <StatTile label="Screenable names" value={fmt(D.totalNames)} delta={n.delta ?? null} spark={n.spark} note="incl. aliases" />
      <StatTile label="Sources online" value={<>{S.sourcesLoaded}<small>/{S.sourcesTotal}</small></>} delta={null} note={failed ? `${failed} failed tonight` : 'all fetches succeeded'} />
      <StatTile label="Countries" value={S.countries} delta={c.delta ?? null} spark={c.spark} note="by nationality or address" />
      {pep && <StatTile label="Politically exposed" value={fmt(D.unpublished)} delta={null} note="counted, not published" />}
      <StatTile label="Programs" value={fmt(D.distinctPrograms)} delta={null} note="distinct, across lists" />
      <StatTile label="Ingest time" value={S.ingestMs == null ? '—' : ms(S.ingestMs)} delta={null} note="all lists, fetched in parallel" />
    </div>
  );
}
