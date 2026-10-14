import { useEffect, useRef, useState } from 'react';
import * as d3 from 'd3';
import { SIEVE } from '../data/snapshot';
import { fmt } from '../lib/format';

/**
 * Fetch time of every list in tonight's snapshot, drawn at the width of its container so that the labels keep the
 * page's type size. Lists are fetched in parallel, so bars start together.
 */
export function IngestChart() {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(640);
  useEffect(() => {
    const el = ref.current; if (!el) return;
    const ro = new ResizeObserver(es => setW(Math.max(320, Math.floor(es[0].contentRect.width))));
    ro.observe(el); return () => ro.disconnect();
  }, []);
  const rows = SIEVE.sources.filter(s => s.fetchMs != null).sort((a, b) => (b.fetchMs ?? 0) - (a.fetchMs ?? 0));
  if (!rows.length) return <span className="muted small">No fetch timings in this snapshot.</span>;
  const tmax = (d3.max(rows, r => r.fetchMs!) ?? 1) * 1.04, rh = 19, lw = Math.min(170, Math.round(w * 0.32)), h = rows.length * rh + 24;
  const x = d3.scaleLinear([0, tmax], [lw, w - 12]);
  const fill = (s: (typeof rows)[number]) => (s.status === 'failed' ? 'var(--red)' : s.status !== 'loaded' ? 'var(--amber)' : s.fetchMs! > 3000 ? 'var(--map-2)' : 'var(--map-1)');
  return (
    <div ref={ref}>
      <svg width={w} height={h} style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 11 }} role="img" aria-label="Fetch time per list">
        {x.ticks(Math.max(3, Math.floor((w - lw) / 90))).map(t => <g key={t}><line x1={x(t)} x2={x(t)} y1={0} y2={h - 20} stroke="var(--border)" /><text x={x(t)} y={h - 6} textAnchor="middle" fill="var(--muted)">{t >= 1000 ? (t / 1000).toFixed(1) + 's' : t + 'ms'}</text></g>)}
        {rows.map((r, i) => <g key={r.id}><text x={lw - 8} y={i * rh + 13} textAnchor="end" fill="var(--text-2)">{r.name}</text><rect x={x(0)} y={i * rh + 3} width={Math.max(2, x(r.fetchMs!) - x(0))} height={rh - 6} rx={2} fill={fill(r)}><title>{`${r.name}: ${fmt(r.fetchMs!)} ms`}</title></rect></g>)}
      </svg>
    </div>
  );
}
