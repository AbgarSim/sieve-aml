import { useCallback, useRef, useState } from 'react';

export function useToast() {
  const [msg, setMsg] = useState<string | null>(null);
  const t = useRef<number>();
  const show = useCallback((m: string) => { setMsg(m); window.clearTimeout(t.current); t.current = window.setTimeout(() => setMsg(null), 1800); }, []);
  const node = <div className={'toast' + (msg ? ' on' : '')} role="status">{msg}</div>;
  return { show, node };
}
