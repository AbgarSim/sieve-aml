import type { ReactNode } from 'react';

export function HBar({ label, pct, value, accent }: { label: ReactNode; pct: number; value: ReactNode; accent?: boolean }) {
  return (
    <div className={'hb' + (accent ? ' acc' : '')}>
      <span className="lbl">{label}</span>
      <span className="tr"><i style={{ width: `${pct}%` }} /></span>
      <span className="v num">{value}</span>
    </div>
  );
}
