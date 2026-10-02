import * as d3 from 'd3';

export interface Series { pts: [number, number][]; color: string; label?: string }
interface Props { w?: number; h?: number; series: Series[]; xLog?: boolean; yLog?: boolean; step?: boolean; yFmt?: (d: number) => string; xFmt?: (d: number) => string; yLabel: string; xLabel: string; hideLastDot?: boolean }

export function LineChart({ w = 420, h = 200, series, xLog, yLog, step, yFmt = d3.format('~s'), xFmt = d3.format('~s'), yLabel, xLabel, hideLastDot }: Props) {
  const m = { t: 12, r: 14, b: 30, l: 54 };
  const allx = series.flatMap(s => s.pts.map(p => p[0])), ally = series.flatMap(s => s.pts.map(p => p[1]));
  const x = (xLog ? d3.scaleLog() : d3.scaleLinear()).domain(d3.extent(allx) as [number, number]).range([m.l, w - m.r]);
  const y = (yLog ? d3.scaleLog() : d3.scaleLinear()).domain(yLog ? (d3.extent(ally) as [number, number]) : [0, d3.max(ally) as number]).nice().range([h - m.b, m.t]);
  const line = d3.line<[number, number]>().x(p => x(p[0])).y(p => y(p[1])).curve(step ? d3.curveStepAfter : d3.curveMonotoneX);
  const yt = yLog ? y.ticks(4).filter((d, _i, a) => a.length <= 5 || Number.isInteger(Math.log10(d))) : y.ticks(4);
  const xt = xLog ? allx.filter((v, i, a) => i === 0 || i === a.length - 1 || Number.isInteger(Math.log10(v))) : x.ticks(5);
  return (
    <svg viewBox={`0 0 ${w} ${h}`} style={{ width: '100%', height: 'auto', fontFamily: "'JetBrains Mono', monospace", fontSize: 10 }} role="img" aria-label={`${yLabel} vs ${xLabel}`}>
      {yt.map(t => <g key={t}><line x1={m.l} x2={w - m.r} y1={y(t)} y2={y(t)} stroke="var(--border)" /><text x={m.l - 6} y={y(t) + 3} textAnchor="end" fill="var(--muted)">{yFmt(t)}</text></g>)}
      {xt.map(t => <text key={t} x={x(t)} y={h - m.b + 14} textAnchor="middle" fill="var(--muted)">{xFmt(t)}</text>)}
      <text x={w - m.r} y={h - 4} textAnchor="end" fill="var(--muted)">{xLabel}</text>
      {series.map((s, si) => {
        const last = s.pts[s.pts.length - 1];
        return (
          <g key={si}>
            <path d={line(s.pts) ?? ''} fill="none" stroke={s.color} strokeWidth={1.8} strokeLinejoin="round" />
            {s.pts.map((p, i) => (hideLastDot && i === s.pts.length - 1 ? null : <circle key={i} cx={x(p[0])} cy={y(p[1])} r={2.4} fill={s.color} />))}
            {s.label && <text x={x(last[0]) - 4} y={y(last[1]) - 8} textAnchor="end" fill={s.color} fontWeight={600}>{s.label}</text>}
          </g>
        );
      })}
    </svg>
  );
}
