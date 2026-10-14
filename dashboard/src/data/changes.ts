// The nightly change log written by `sieve snapshot`: per night, the records that appeared on a list, left it or changed.
import { useEffect, useState } from 'react';
import { fetchJson } from './snapshot';

/** A record that appeared, left or changed: key, name, type code of the search index, countries and program codes. */
export interface ChangeRecord { k: string; n?: string; t?: 'I' | 'E' | 'V' | 'A' | 'C' | 'O' | 'W' | 'S'; c?: string[]; p?: string[] }
export type ChangeKind = 'added' | 'removed' | 'changed';
/** One night: exact counts, counts per list as [added, removed, changed], and the first records of each kind. */
export interface Night { date: string; added: number; removed: number; changed: number; bySource?: Record<string, [number, number, number]>; records?: Partial<Record<ChangeKind, ChangeRecord[]>> }
export interface RawChanges { formatVersion: number; generatedAt: string; sample?: boolean; nights?: Night[] }

export const KINDS: ChangeKind[] = ['added', 'removed', 'changed'];
export const KIND_LABEL: Record<ChangeKind, string> = { added: 'New listings', removed: 'Delisted', changed: 'Changed' };
/** How many records of a kind a night's row holds, as the snapshot caps the list. */
export const KIND_INDEX: Record<ChangeKind, 0 | 1 | 2> = { added: 0, removed: 1, changed: 2 };

let pending: Promise<RawChanges | null> | null = null;

/** Loads changes.json once. A snapshot written before the file existed has none (null). */
export function loadChanges(): Promise<RawChanges | null> {
  pending ??= fetchJson<RawChanges>('changes.json')
    .catch(e => { if (String(e).includes('HTTP 404')) return null; throw e; })
    .catch(e => { pending = null; throw e; });
  return pending;
}

/** The change log, loaded on first use: undefined while loading, null when the snapshot has none. */
export function useChanges(): { changes: RawChanges | null | undefined; error: string | null } {
  const [changes, setChanges] = useState<RawChanges | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { let live = true; loadChanges().then(c => live && setChanges(c), (e: unknown) => live && setError(e instanceof Error ? e.message : String(e))); return () => { live = false; }; }, []);
  return { changes, error };
}

/** The nights, newest first. */
export const nightsOf = (c: RawChanges | null | undefined): Night[] => [...(c?.nights ?? [])].sort((a, b) => b.date.localeCompare(a.date));

export const sourceOf = (key: string) => key.slice(0, key.indexOf('/'));
/** The entity page of a record that is still published. */
export const recordPath = (key: string) => { const slash = key.indexOf('/'); return `/entity/${key.slice(0, slash)}/${encodeURIComponent(key.slice(slash + 1))}`; };
