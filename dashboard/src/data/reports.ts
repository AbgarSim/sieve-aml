import { fetchJson } from './snapshot';

/** Where people report a suspected breach: the issue form in the repository. */
export const REPORT_FORM = 'https://github.com/AbgarSim/sieve-aml/issues/new?template=breach_report.yml';

/**
 * A suspected breach reported by a user and accepted by the maintainer after review, as
 * reports.json holds it. Not an official finding: it is what the reporter's sources show.
 */
export interface BreachReport {
  id: string;
  issue: number;
  /** The GitHub issue the report was made in. */
  url: string;
  acceptedAt: string;
  party: string;
  programs: string[];
  targets: string[];
  otherProgram?: boolean;
  summary: string;
  sources: string[];
  when?: string;
  where?: string;
}

/** reports.json: accepted reports, newest first. */
export interface RawReports { formatVersion: number; generatedAt?: string; sample?: boolean; reports: BreachReport[] }

let pending: Promise<RawReports> | null = null;

/** Loads reports.json once. A build that collected no reports has none. */
export function loadReports(): Promise<RawReports> {
  pending ??= fetchJson<RawReports>('reports.json')
    .catch(e => { if (String(e).includes('HTTP 404')) return { formatVersion: 1, reports: [] }; throw e; })
    .catch(e => { pending = null; throw e; });
  return pending;
}

/** The report form, with the program a selected country stands for already picked. */
export function reportLink(program?: string) {
  return REPORT_FORM + (program ? '&programs=' + encodeURIComponent(program) : '');
}

/** The form's program for each targeted country, so a selected country can be picked in advance. */
export const PROGRAM_BY_COUNTRY: Record<string, string> = {
  RU: 'Russia', BY: 'Belarus', IR: 'Iran', KP: 'North Korea', SY: 'Syria', CU: 'Cuba', VE: 'Venezuela', MM: 'Burma',
  SD: 'Sudan', SS: 'South Sudan', IQ: 'Iraq', LY: 'Libya', YE: 'Yemen', AF: 'Afghanistan', LB: 'Lebanon', NI: 'Nicaragua',
  ML: 'Mali', SO: 'Somalia', CD: 'DR Congo', CF: 'Central African Republic', ZW: 'Zimbabwe',
};
