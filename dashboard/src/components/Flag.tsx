import { useState } from 'react';

export function Flag({ cc, className = 'flag' }: { cc?: string; className?: string }) {
  const [err, setErr] = useState(false);
  if (!cc) return null;
  const c = cc.toLowerCase();
  if (err || c === 'un' || c === 'eu') return <span className="flag-txt" title={c.toUpperCase()}>{c.toUpperCase()}</span>;
  return <img className={className} src={`https://flagcdn.com/w20/${c}.png`} srcSet={`https://flagcdn.com/w40/${c}.png 2x`} width={16} height={12} alt="" loading="lazy" onError={() => setErr(true)} />;
}
