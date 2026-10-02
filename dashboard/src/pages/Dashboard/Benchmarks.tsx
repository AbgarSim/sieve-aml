import { Fragment } from 'react';
import * as d3 from 'd3';
import { LineChart } from '../../components/LineChart';
import { SIEVE } from '../../data/snapshot';
import { BENCH } from '../../data/benchmarks';
import { fmt } from '../../lib/format';

const DOCS = 'https://github.com/AbgarSim/sieve-aml/blob/main/' + BENCH.source;

/** Fetch time of every list in tonight's snapshot. Lists are fetched in parallel, so bars start together. */
function IngestChart() {
  const rows = SIEVE.sources.filter(s => s.fetchMs != null).sort((a, b) => (b.fetchMs ?? 0) - (a.fetchMs ?? 0));
  if (!rows.length) return <span className="muted small">No fetch timings in this snapshot.</span>;
  const tmax = (d3.max(rows, r => r.fetchMs!) ?? 1) * 1.04, w = 600, rh = 11, h = rows.length * rh + 22, lw = 118;
  const x = d3.scaleLinear([0, tmax], [lw, w - 8]);
  const fill = (s: (typeof rows)[number]) => (s.status === 'failed' ? 'var(--red)' : s.status !== 'loaded' ? 'var(--amber)' : s.fetchMs! > 3000 ? 'var(--map-2)' : 'var(--map-1)');
  return (
    <svg viewBox={`0 0 ${w} ${h}`} style={{ width: '100%', height: 'auto', fontFamily: "'JetBrains Mono', monospace", fontSize: 9 }} role="img" aria-label="Fetch time per list">
      {x.ticks(6).map(t => <g key={t}><line x1={x(t)} x2={x(t)} y1={0} y2={h - 18} stroke="var(--border)" /><text x={x(t)} y={h - 5} textAnchor="middle" fill="var(--muted)">{t >= 1000 ? (t / 1000).toFixed(1) + 's' : t + 'ms'}</text></g>)}
      {rows.map((r, i) => <g key={r.id}><text x={lw - 8} y={i * rh + 8} textAnchor="end" fill="var(--text-2)">{r.name}</text><rect x={x(0)} y={i * rh + 1} width={Math.max(2, x(r.fetchMs!) - x(0))} height={rh - 3} rx={2} fill={fill(r)}><title>{`${r.name}: ${fmt(r.fetchMs!)} ms`}</title></rect></g>)}
    </svg>
  );
}

function Card({ t, s, foot, span, children }: { t: string; s: string; foot: React.ReactNode; span?: boolean; children: React.ReactNode }) {
  return <div className="card" style={span ? { gridColumn: '1 / -1' } : undefined}><div className="card-h"><h3>{t}</h3><span className="xs muted">{s}</span></div><div className="card-b">{children}</div><div className="card-f">{foot}</div></div>;
}

export function Benchmarks() {
  const B = BENCH, v1 = B.versions[0], v3 = B.versions[B.versions.length - 1];
  const vlabel: Record<number, string> = { 1: 'v1 linear scan', 2: 'v2 n-gram index', 3: 'v3 optimised' };
  const docs = <>{B.env} · <a href={DOCS} target="_blank" rel="noopener">published results</a></>;
  return (
    <>
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-b" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 24, alignItems: 'center' }}>
          <div>
            <div className="small muted" style={{ fontWeight: 500 }}>v1 → v3, peak throughput</div>
            <div className="num" style={{ fontSize: 56, fontWeight: 500, color: 'var(--heading)', lineHeight: 1, margin: '6px 0 10px', letterSpacing: '-.03em' }}>{Math.round(v3.rps / v1.rps / 10) * 10}<span style={{ color: 'var(--accent-text)' }}>×</span> <span style={{ fontSize: 22, fontWeight: 500 }}>more req/s</span></div>
            <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr 1fr', gap: '4px 16px', fontSize: 13 }} className="num">{B.versions.map(x => <Fragment key={x.v}><span className="muted">{x.v}</span><span>{x.ms} ms</span><span className="muted">{fmt(x.rps)} req/s</span></Fragment>)}</div>
          </div>
          <LineChart w={560} h={190} step yLog hideLastDot series={[{ pts: [...B.versions.map((x, i) => [i + 1, x.ms] as [number, number]), [3.6, v3.ms]], color: 'var(--accent)' }]} yFmt={d => d + ' ms'} xFmt={d => vlabel[d] ?? ''} yLabel="latency" xLabel="release" />
        </div>
        <div className="card-f">{docs} · peak throughput at 200 concurrent requests</div>
      </div>
      <div className="grid g3" style={{ gridAutoFlow: 'dense' }}>
        <Card t="Concurrency scaling" s="v3 throughput, 1 → 200 concurrent" foot={docs}><LineChart xLog series={[{ pts: B.rampUp.map(r => [r[0], r[1]]), color: 'var(--map-1)' }]} yLabel="req/s" xLabel="concurrency" xFmt={String} /></Card>
        <Card t="Latency p50 / p99" s="per request incl. HTTP, ms" foot={docs}><LineChart xLog yLog series={[{ pts: B.rampUp.map(r => [r[0], r[2]]), color: 'var(--map-1)', label: 'p50' }, { pts: B.rampUp.map(r => [r[0], r[3]]), color: 'var(--amber)', label: 'p99' }]} yLabel="ms" xLabel="concurrency" xFmt={String} yFmt={String} /></Card>
        <Card t="Threshold sensitivity" s="v3 throughput by match threshold" foot={docs}>
          <table className="tbl" style={{ fontSize: 12 }}>
            <thead><tr><th>Threshold</th><th className="r">req/s</th><th className="r">avg ms</th><th className="r">p99 ms</th></tr></thead>
            <tbody>{B.thresholds.map(r => <tr key={r[0]}><td className="num" style={{ color: 'var(--heading)' }}>{r[0].toFixed(2)}</td><td className="r num">{fmt(r[1])}</td><td className="r num">{r[2]}</td><td className="r num">{r[3]}</td></tr>)}</tbody>
          </table>
        </Card>
        <Card t="Ingest timeline" s={`tonight's fetch of ${SIEVE.sources.length} lists, in parallel`} foot={<>Measured by the nightly snapshot run · {SIEVE.snapshot.date}</>} span><IngestChart /></Card>
      </div>
    </>
  );
}
