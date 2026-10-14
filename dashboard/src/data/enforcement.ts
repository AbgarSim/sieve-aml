import { fetchJson } from './snapshot';

export type Authority = 'OFAC' | 'OFSI' | 'FINCEN' | 'DOJ';
export type Kind = 'CIVIL_PENALTY' | 'DISCLOSURE' | 'CRIMINAL_CASE';

/** A published enforcement action for a sanctions breach, as enforcement.json holds it. */
export interface EnforcementAction {
  id: string;
  source: Authority;
  /** Country of the enforcing authority. */
  cc: string;
  kind: Kind;
  date: string;
  party?: string;
  amount?: number;
  currency: string;
  violations?: number;
  /** Programs breached: countries or themes. */
  programs: string[];
  /** Countries those programs target. */
  targets: string[];
  summary?: string;
  url: string;
}

export interface EnforcementSource { id: Authority; name: string; authority: string; cc: string; url: string; status: 'OK' | 'FAILED'; actions: number; fetchMs?: number; error?: string }

/** A news article reporting a suspected breach. Unverified: what the article says, not what an authority found. */
export interface SuspectedBreach { url: string; title: string; domain: string; seenAt: string; programs: string[]; targets: string[] }

/** enforcement.json: newest first. */
export interface RawEnforcement { formatVersion: number; generatedAt?: string; sample?: boolean; sources: EnforcementSource[]; actions: EnforcementAction[]; suspected?: SuspectedBreach[] }

export const KIND_LABEL: Record<Kind, string> = { CIVIL_PENALTY: 'Civil penalty', DISCLOSURE: 'Disclosure', CRIMINAL_CASE: 'Criminal case' };

let pending: Promise<RawEnforcement | null> | null = null;

/** Loads enforcement.json once. A snapshot written before the file existed has none (null). */
export function loadEnforcement(): Promise<RawEnforcement | null> {
  pending ??= fetchJson<RawEnforcement>('enforcement.json')
    .catch(e => { if (String(e).includes('HTTP 404')) return null; throw e; })
    .catch(e => { pending = null; throw e; });
  return pending;
}

/** "$11.7M", "£465K". */
export function money(amount: number, currency: string) {
  const sym = currency === 'GBP' ? '£' : currency === 'EUR' ? '€' : '$';
  if (amount >= 1e9) return sym + (amount / 1e9).toFixed(amount >= 1e10 ? 0 : 1) + 'B';
  if (amount >= 1e6) return sym + (amount / 1e6).toFixed(amount >= 1e7 ? 0 : 1) + 'M';
  if (amount >= 1e3) return sym + Math.round(amount / 1e3) + 'K';
  return sym + amount;
}
