import { useEffect, useState } from 'react';
import { loadIndex, type Index } from './search';

/** The search index, loaded on first use. */
export function useIndex(): { ix: Index | null; error: string | null } {
  const [ix, setIx] = useState<Index | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { let live = true; loadIndex().then(i => live && setIx(i), (e: unknown) => live && setError(e instanceof Error ? e.message : String(e))); return () => { live = false; }; }, []);
  return { ix, error };
}
