import { Fragment } from 'react';
import { LineChart } from '../../components/LineChart';
import { BENCH } from '../../data/benchmarks';
import { fmt } from '../../lib/format';

const DOCS = 'https://github.com/AbgarSim/sieve-aml/blob/main/' + BENCH.source;

function Card({ t, s, foot, children }: { t: string; s: string; foot: React.ReactNode; children: React.ReactNode }) {
  return <div className="card"><div className="card-h"><h3>{t}</h3><span className="xs muted">{s}</span></div><div className="card-b">{children}</div><div className="card-f">{foot}</div></div>;
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
      </div>
    </>
  );
}
