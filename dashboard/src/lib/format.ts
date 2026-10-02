export const fmt = (n: number) => n.toLocaleString('en-US');
export const ago = (d: number) => (d === 0 ? 'today' : d === 1 ? 'yesterday' : `${d}d ago`);
/** Time of day of an ISO timestamp, in UTC. */
export const clock = (iso: string) => new Date(iso).toISOString().slice(11, 19);
export const ms = (v: number) => (v >= 1000 ? (v / 1000).toFixed(1) + ' s' : v + ' ms');
export const host = (u: string) => u.replace(/^https?:\/\//, '').split('/')[0];
export const cssVar = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
