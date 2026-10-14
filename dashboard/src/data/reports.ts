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

/** The site's report form, with the program a selected country stands for already picked. */
export function reportLink(program?: string) {
  return '/report' + (program ? '?program=' + encodeURIComponent(program) : '');
}

/** The form's program for each targeted country, so a selected country can be picked in advance. */
export const PROGRAM_BY_COUNTRY: Record<string, string> = {
  RU: 'Russia', BY: 'Belarus', IR: 'Iran', KP: 'North Korea', SY: 'Syria', CU: 'Cuba', VE: 'Venezuela', MM: 'Burma',
  SD: 'Sudan', SS: 'South Sudan', IQ: 'Iraq', LY: 'Libya', YE: 'Yemen', AF: 'Afghanistan', LB: 'Lebanon', NI: 'Nicaragua',
  ML: 'Mali', SO: 'Somalia', CD: 'DR Congo', CF: 'Central African Republic', ZW: 'Zimbabwe',
};

/** The programs a report can name, in the order the form lists them (as the issue form does). */
export const REPORT_PROGRAMS = [
  'Russia', 'Belarus', 'Iran', 'North Korea', 'Syria', 'Cuba', 'Venezuela', 'Burma', 'Sudan', 'South Sudan', 'Iraq', 'Libya',
  'Yemen', 'Afghanistan', 'Lebanon', 'Nicaragua', 'Mali', 'Somalia', 'DR Congo', 'Central African Republic', 'Zimbabwe',
  'Terrorism', 'Narcotics', 'Proliferation', 'Cyber', 'Human rights', 'Other',
];

export interface ReportDraft { party: string; programs: string[]; when: string; where: string; what: string; sources: string }

/** The http(s) links in the sources field. */
export const sourceLinks = (text: string) => [...new Set((text.match(/https?:\/\/[^\s<>()"'`\]]+/g) ?? []).map(u => u.replace(/[.,;:!?]+$/, '')))];

/** What keeps a draft from being sent, as the review workflow checks it too. */
export function draftProblems(d: ReportDraft) {
  const p: string[] = [];
  if (!d.party.trim()) p.push('Say who is suspected.');
  if (!d.programs.length) p.push('Pick at least one sanctions program.');
  if (!d.what.trim()) p.push('Say what happened.');
  if (!sourceLinks(d.sources).length) p.push('Add at least one public source link.');
  return p;
}

const field = (label: string, value: string) => `### ${label}\n\n${value.trim() || '_No response_'}`;

/**
 * The GitHub page that files a draft as a report: a new issue whose body has the same headings
 * as the issue form, so the review workflow reads it the same way.
 */
export function issueUrl(d: ReportDraft) {
  const body = [
    field('Who is suspected', d.party),
    field('Sanctions programs', d.programs.join(', ')),
    field('When', d.when),
    field('Where', d.where),
    field('What happened', d.what.slice(0, 1500)),
    field('Public sources', sourceLinks(d.sources).slice(0, 10).join('\n')),
    '### Before you submit\n\n- [X] Everything in this report comes from the public sources I linked.\n- [X] I understand this issue is public, and that Sieve shows only reports the maintainer has reviewed and accepted.',
    '<sub>Sent from the report form on the Sieve site.</sub>',
  ].join('\n\n');
  const title = '[Breach report] ' + d.party.trim().slice(0, 120);
  return `https://github.com/AbgarSim/sieve-aml/issues/new?labels=breach-report&title=${encodeURIComponent(title)}&body=${encodeURIComponent(body)}`;
}
