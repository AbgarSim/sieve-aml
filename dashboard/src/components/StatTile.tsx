import type { ReactNode } from 'react';
import { Delta } from './Badges';
import { Sparkline } from './Sparkline';

export function StatTile({ label, value, delta, spark, note }: { label: string; value: ReactNode; delta?: number | null; spark?: number[]; note?: string }) {
  return (
    <div className="card kpi">
      <div className="l">{label}</div>
      <div className="v num">{value}</div>
      <div className="r">
        {delta == null ? <span className="small muted">{note}</span> : <><Delta n={delta} /><span className="xs muted">vs previous</span></>}
        {spark && <Sparkline data={spark} />}
      </div>
    </div>
  );
}
