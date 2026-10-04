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
  const d = D.distinctEntities == null ? null : trend(r => r.distinctEntities ?? r.totalEntities);
  const failed = D.sources.filter(s => s.status === 'failed').length;
  const topic = (k: string) => D.byTopic.find(t => t[0] === k)?.[1] ?? 0;
  const peps = topic('PEP'), rcas = topic('RCA'), pep = D.unpublished > 0;
  const tiles = 6 + (pep ? 1 : 0) + (d ? 1 : 0);
  return (
    <div className={'grid g' + tiles}>
      <StatTile label="Entities" value={fmt(D.totalEntities)} delta={e.delta ?? null} spark={e.spark} note={first} />
      {d && <StatTile label="Distinct entities" value={fmt(D.distinctEntities!)} delta={d.delta ?? null} spark={d.spark} note={D.onSeveralLists ? `${fmt(D.onSeveralLists)} on several lists` : 'matched across lists'} />}
      <StatTile label="Screenable names" value={fmt(D.totalNames)} delta={n.delta ?? null} spark={n.spark} note="incl. aliases" />
      <StatTile label="Sources online" value={<>{S.sourcesLoaded}<small>/{S.sourcesTotal}</small></>} delta={null} note={failed ? `${failed} failed tonight` : 'all fetches succeeded'} />
      <StatTile label="Countries" value={S.countries} delta={c.delta ?? null} spark={c.spark} note="by nationality or address" />
      {pep && <StatTile label="Politically exposed" value={fmt(peps || D.unpublished)} delta={null} note={rcas ? `+ ${fmt(rcas)} relatives and associates, not published` : 'counted, not published'} />}
      <StatTile label="Programs" value={fmt(D.distinctPrograms)} delta={null} note="distinct, across lists" />
      <StatTile label="Ingest time" value={S.ingestMs == null ? '—' : ms(S.ingestMs)} delta={null} note="all lists, fetched in parallel" />
    </div>
  );
}
