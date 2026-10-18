// Weighs facts beyond the name (date of birth, identifiers, country, place of birth, address,
// gender) into a name match score. Mirrors dev.sieve.match.EvidenceScorer; change both together.
import { jaroWinkler as jw, normalize as norm, tokens } from '../lib/jw';
import type { Entry } from './search';

export interface Criteria { dob?: string; placeOfBirth?: string; country?: string; address?: string; identifier?: string; gender?: 'M' | 'F' | 'O' }
export type Outcome = 'MATCH' | 'PARTIAL' | 'MISMATCH' | 'NOT_ON_RECORD';
export interface FieldMatch { field: keyof Criteria; outcome: Outcome; value: string }

/** Share of the gap to 1.0 that an agreeing fact closes. */
const W = { dobExact: 0.4, dobYear: 0.25, dobNear: 0.1, identifier: 0.6, country: 0.15, placeOfBirth: 0.15, address: 0.15, addressPartial: 0.05, gender: 0.05 };
/** Share of the score that a contradicting fact removes. */
const P = { dob: 0.3, identifier: 0.05, country: 0.15, placeOfBirth: 0.05, address: 0.05, gender: 0.25 };
/** The lowest name score that facts beyond the name can lift to a match. */
export const MIN_NAME_SCORE = 0.7;

export const FIELD_LABEL: Record<keyof Criteria, string> = { dob: 'Date of birth', placeOfBirth: 'Place of birth', country: 'Country', address: 'Address', identifier: 'ID number', gender: 'Gender' };

const given = (c: Criteria) => (Object.keys(c) as (keyof Criteria)[]).filter(k => c[k] && String(c[k]).trim());
export const hasCriteria = (c: Criteria) => given(c).length > 0;

interface PDate { y: number; m?: number; d?: number }
/** Parses YYYY, YYYY-MM, YYYY-MM-DD, DD.MM.YYYY or DD/MM/YYYY; undefined when it is none of them. */
export function parseDate(s: string): PDate | undefined {
  const v = s.trim();
  let m = /^(\d{4})(?:-(\d{1,2})(?:-(\d{1,2}))?)?$/.exec(v);
  if (m) return valid({ y: +m[1], m: m[2] ? +m[2] : undefined, d: m[3] ? +m[3] : undefined });
  m = /^(\d{1,2})[./](\d{1,2})[./](\d{4})$/.exec(v);
  if (m) return valid({ y: +m[3], m: +m[2], d: +m[1] });
  return undefined;
}
const valid = (p: PDate) => (p.m === undefined || (p.m >= 1 && p.m <= 12)) && (p.d === undefined || (p.d >= 1 && p.d <= 31)) ? p : undefined;

/** The name score floor for candidates: below it no combination of the given facts reaches the threshold. */
export function candidateThreshold(c: Criteria, threshold: number): number {
  let rest = 1;
  for (const k of given(c)) rest *= 1 - ({ dob: W.dobExact, identifier: W.identifier, country: W.country, placeOfBirth: W.placeOfBirth, address: W.address, gender: W.gender } as const)[k];
  return Math.min(threshold, Math.max(Math.min(MIN_NAME_SCORE, threshold), 1 - (1 - threshold) / rest));
}

const raise = (s: number, w: number) => s + (1 - s) * w;
const lower = (s: number, p: number) => s * (1 - p);
const apply = (s: number, o: Outcome, match: number, partial: number, mismatch: number) => o === 'MATCH' ? raise(s, match) : o === 'PARTIAL' ? raise(s, partial) : o === 'MISMATCH' ? lower(s, mismatch) : s;

/** Re-scores a name score for one index entry; the facts' outcomes come back in the order given. */
export function weigh(e: Entry, nameScore: number, c: Criteria): { s: number; evidence: FieldMatch[] } {
  let s = nameScore;
  const evidence: FieldMatch[] = [];
  const q = parseDate(c.dob ?? '');
  if (q) {
    const f = dob(q, e.dobs); evidence.push(f);
    s = f.outcome === 'MATCH' ? raise(s, q.d !== undefined && !f.value.endsWith('-01-01') ? W.dobExact : W.dobYear) : apply(s, f.outcome, 0, W.dobNear, P.dob);
  }
  if (c.identifier?.trim()) {
    const f = identifier(c.identifier, e.ids); evidence.push(f);
    s = apply(s, f.outcome, W.identifier, W.identifier, P.identifier);
  }
  if (c.country) {
    const f: FieldMatch = !e.countries.length ? { field: 'country', outcome: 'NOT_ON_RECORD', value: '' } : e.countries.includes(c.country) ? { field: 'country', outcome: 'MATCH', value: c.country } : { field: 'country', outcome: 'MISMATCH', value: e.countries.join(', ') };
    evidence.push(f); s = apply(s, f.outcome, W.country, W.country, P.country);
  }
  if (c.placeOfBirth?.trim()) {
    const f = text('placeOfBirth', c.placeOfBirth, e.birthplaces); evidence.push(f);
    s = apply(s, f.outcome, W.placeOfBirth, W.placeOfBirth / 2, P.placeOfBirth);
  }
  if (c.address?.trim()) {
    const f = text('address', c.address, e.addresses); evidence.push(f);
    s = apply(s, f.outcome, W.address, W.addressPartial, P.address);
  }
  if (c.gender) {
    const f: FieldMatch = !e.gender ? { field: 'gender', outcome: 'NOT_ON_RECORD', value: '' } : { field: 'gender', outcome: e.gender === c.gender ? 'MATCH' : 'MISMATCH', value: GENDER[e.gender] ?? e.gender };
    evidence.push(f); s = apply(s, f.outcome, W.gender, W.gender, P.gender);
  }
  return { s: Math.max(0, Math.min(1, s)), evidence };
}

const GENDER: Record<string, string> = { M: 'male', F: 'female', O: 'other' };
const RANK: Outcome[] = ['MATCH', 'PARTIAL', 'MISMATCH'];

function dob(q: PDate, dates: string[]): FieldMatch {
  if (!dates.length) return { field: 'dob', outcome: 'NOT_ON_RECORD', value: '' };
  let best: FieldMatch = { field: 'dob', outcome: 'MISMATCH', value: dates.join(', ') };
  for (const d of dates) {
    const [y, m, day] = d.split('-').map(Number);
    const o = compareDate(q, y, m, day);
    if (RANK.indexOf(o) < RANK.indexOf(best.outcome)) best = { field: 'dob', outcome: o, value: d };
  }
  return best;
}

/** A record date of 1 January stands for a year-only date on most lists, so it agrees with any date that year. */
function compareDate(q: PDate, y: number, m: number, d: number): Outcome {
  if (q.y === y) {
    if (q.m === undefined || (m === 1 && d === 1)) return 'MATCH';
    if (q.m === m) return q.d === undefined || q.d === d ? 'MATCH' : 'PARTIAL';
    if (q.d !== undefined && q.d === m && q.m === d) return 'PARTIAL';
    return 'MISMATCH';
  }
  return Math.abs(q.y - y) === 1 ? 'PARTIAL' : 'MISMATCH';
}

/** An identifier with case, spaces and punctuation removed, as the index stores it. */
export const identifierKey = (s: string) => s.toUpperCase().replace(/[^\p{L}\p{N}]/gu, '');

function identifier(q: string, ids: string[]): FieldMatch {
  if (!ids.length) return { field: 'identifier', outcome: 'NOT_ON_RECORD', value: '' };
  const wanted = q.split(/[,;]/).map(identifierKey).filter(Boolean);
  const hit = ids.find(i => wanted.includes(i));
  return hit ? { field: 'identifier', outcome: 'MATCH', value: hit } : { field: 'identifier', outcome: 'MISMATCH', value: ids.join(', ') };
}

const words = (s: string) => tokens(norm(s)).filter(w => w.length >= 2);

/** Free text compared by words: every query word found in a record value is a match, some is partial, none a mismatch. */
function text(field: 'placeOfBirth' | 'address', q: string, values: string[]): FieldMatch {
  const vs = values.filter(v => v.trim());
  const want = words(q);
  if (!vs.length || !want.length) return { field, outcome: 'NOT_ON_RECORD', value: '' };
  let best: FieldMatch = { field, outcome: 'MISMATCH', value: vs.join('; ') }, share = 0;
  for (const v of vs) {
    const have = words(v);
    const found = want.filter(w => have.some(h => h === w || (w.length >= 4 && jw(h, w) >= 0.9))).length / want.length;
    if (found > share) { share = found; best = { field, outcome: found >= 0.999 ? 'MATCH' : 'PARTIAL', value: v }; }
  }
  return best;
}
