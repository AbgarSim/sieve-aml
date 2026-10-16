// Breach reports sent through the "Report a suspected sanctions breach" issue form.
//
// A report is published only after review: the maintainer labels its issue `report-accepted`,
// the Breach report workflow then freezes the report as a comment by github-actions[bot], and
// the nightly Dashboard build collects those frozen copies into reports.json. Removing the
// label unpublishes the report on the next build. Edits the reporter makes after acceptance
// are not published, because only the bot's copy is read.
//
//   node scripts/breach-reports.mjs opened   <issue>   acknowledges a new report, adds the label
//   node scripts/breach-reports.mjs accept   <issue>   freezes an accepted report
//   node scripts/breach-reports.mjs collect  <file>    writes reports.json from accepted issues
//
// Needs GITHUB_TOKEN and GITHUB_REPOSITORY.

import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const REPORT_LABEL = 'breach-report';
export const ACCEPTED_LABEL = 'report-accepted';
export const MARKER = '<!-- sieve-breach-report v1 -->';
const BOT = 'github-actions[bot]';

/** Program names as the form lists them, with the country each targets (themes have none). */
export const PROGRAMS = {
  Russia: 'RU', Belarus: 'BY', Iran: 'IR', 'North Korea': 'KP', Syria: 'SY', Cuba: 'CU', Venezuela: 'VE',
  Burma: 'MM', Sudan: 'SD', 'South Sudan': 'SS', Iraq: 'IQ', Libya: 'LY', Yemen: 'YE', Afghanistan: 'AF',
  Lebanon: 'LB', Nicaragua: 'NI', Mali: 'ML', Somalia: 'SO', 'DR Congo': 'CD', 'Central African Republic': 'CF',
  Zimbabwe: 'ZW', Terrorism: null, Narcotics: null, Proliferation: null, Cyber: null, 'Human rights': null,
};

const FIELDS = { 'Who is suspected': 'party', 'Sanctions programs': 'programs', When: 'when', Where: 'where', 'What happened': 'what', 'Public sources': 'sources' };
const MAX = { party: 200, when: 100, where: 200, what: 1500 };
const MAX_SOURCES = 10;

/** The fields of an issue-form body: "### Label" headings, each followed by its value. */
export function parseForm(body) {
  const out = {};
  const parts = (body ?? '').replace(/\r\n/g, '\n').split(/^### /m).slice(1);
  for (const part of parts) {
    const nl = part.indexOf('\n');
    const label = (nl < 0 ? part : part.slice(0, nl)).trim();
    const value = (nl < 0 ? '' : part.slice(nl + 1)).trim();
    if (FIELDS[label]) out[FIELDS[label]] = value === '_No response_' ? '' : value;
  }
  return out;
}

const clip = (s, n) => (s.length > n ? s.slice(0, n - 1).trimEnd() + '…' : s);
const plain = s => s.replace(/<!--[\s\S]*?-->/g, '').replace(/[ \t]+/g, ' ').trim();

/** The http(s) links in the sources field, without duplicates. */
export function links(text) {
  const seen = new Set();
  for (const m of (text ?? '').matchAll(/https?:\/\/[^\s<>()"'`\]]+/g)) {
    const url = m[0].replace(/[.,;:!?]+$/, '');
    try { const u = new URL(url); if (u.protocol === 'http:' || u.protocol === 'https:') seen.add(u.href); } catch { /* not a link */ }
  }
  return [...seen].slice(0, MAX_SOURCES);
}

/**
 * The report a form body holds, or the problems that keep it from being published.
 * @returns {{ report?: object, problems: string[] }}
 */
export function toReport(fields) {
  const problems = [];
  const party = plain(fields.party ?? '');
  if (!party) problems.push('It does not say who is suspected.');
  const named = (fields.programs ?? '').split(',').map(s => s.trim()).filter(s => s && s !== 'None');
  const programs = named.filter(p => p in PROGRAMS);
  if (!named.length) problems.push('It names no sanctions program.');
  const what = plain(fields.what ?? '');
  if (!what) problems.push('It does not say what happened.');
  const sources = links(fields.sources);
  if (!sources.length) problems.push('It has no public source link.');
  if (problems.length) return { problems };
  const report = {
    party: clip(party, MAX.party),
    programs,
    targets: [...new Set(programs.map(p => PROGRAMS[p]).filter(Boolean))],
    summary: clip(what, MAX.what),
    sources,
  };
  if (named.includes('Other')) report.otherProgram = true;
  const when = plain(fields.when ?? ''), where = plain(fields.where ?? '');
  if (when) report.when = clip(when, MAX.when);
  if (where) report.where = clip(where, MAX.where);
  return { report, problems };
}

/** The comment that freezes an accepted report: readable text plus the JSON the build reads. */
export function acceptedComment(report) {
  return [
    MARKER,
    'This report was reviewed and accepted. The next nightly build shows it on the [Monitoring page](https://sieveaml.org/#/monitoring), marked as reported by a user, with the details below. Later edits to the issue are not published; to publish a corrected version, remove the `report-accepted` label and add it again. Removing the label takes the report off the site.',
    '',
    '```json',
    JSON.stringify(report, null, 2),
    '```',
  ].join('\n');
}

/** The report frozen in a bot comment, if it is valid. */
export function frozen(comment) {
  if (comment?.user?.login !== BOT || !comment.body?.includes(MARKER)) return null;
  const m = comment.body.match(/```json\n([\s\S]*?)\n```/);
  if (!m) return null;
  try {
    const r = JSON.parse(m[1]);
    // Checked again so that a hand-edited comment cannot publish anything malformed
    const { report } = toReport({ party: r.party, programs: (r.programs ?? []).concat(r.otherProgram ? ['Other'] : []).join(', '), what: r.summary, sources: (r.sources ?? []).join('\n'), when: r.when, where: r.where });
    return report ?? null;
  } catch { return null; }
}

async function gh(path, init = {}) {
  const r = await fetch(`https://api.github.com/repos/${process.env.GITHUB_REPOSITORY}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  if (!r.ok && r.status !== 404 && r.status !== 422) throw new Error(`${init.method ?? 'GET'} ${path}: HTTP ${r.status} ${await r.text()}`);
  return r.status === 204 ? null : r.json();
}
const post = (path, body) => gh(path, { method: 'POST', body: JSON.stringify(body) });
async function all(path) {
  const out = [];
  for (let page = 1; ; page++) {
    const rows = await gh(`${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
    if (!Array.isArray(rows)) return out;
    out.push(...rows);
    if (rows.length < 100) return out;
  }
}

async function ensureLabels() {
  await post('/labels', { name: REPORT_LABEL, color: 'd93f0b', description: 'A suspected sanctions breach reported through the form' });
  await post('/labels', { name: ACCEPTED_LABEL, color: '0e8a16', description: 'Reviewed breach report: shown on the Monitoring page' });
}

async function opened(number) {
  const issue = await gh(`/issues/${number}`);
  const fields = parseForm(issue.body);
  if (!('party' in fields && 'sources' in fields)) return; // not a breach report
  await ensureLabels();
  await post(`/issues/${number}/labels`, { labels: [REPORT_LABEL] });
  const { problems } = toReport(fields);
  const text = problems.length
    ? `Thank you for the report. Before it can be reviewed it needs a fix:\n\n${problems.map(p => `- ${p}`).join('\n')}\n\nEdit the issue to add what is missing.`
    : 'Thank you for the report. The maintainer reviews every report against its sources. If it is accepted, it appears on the Monitoring page after the next nightly build, marked as reported by a user; if not, this issue is closed with a note.';
  await post(`/issues/${number}/comments`, { body: text });
}

async function accept(number) {
  const issue = await gh(`/issues/${number}`);
  const { report, problems } = toReport(parseForm(issue.body));
  if (!report) {
    await post(`/issues/${number}/comments`, { body: `This report cannot be published as it stands, so the \`${ACCEPTED_LABEL}\` label was removed:\n\n${problems.map(p => `- ${p}`).join('\n')}` });
    await gh(`/issues/${number}/labels/${ACCEPTED_LABEL}`, { method: 'DELETE' });
    return;
  }
  await post(`/issues/${number}/comments`, { body: acceptedComment(report) });
}

async function collect(file) {
  const issues = (await all(`/issues?labels=${ACCEPTED_LABEL}&state=all`)).filter(i => !i.pull_request);
  const reports = [];
  for (const issue of issues) {
    const comments = await all(`/issues/${issue.number}/comments`);
    for (const c of comments.reverse()) {
      const report = frozen(c);
      if (report) { reports.push({ id: `R-${issue.number}`, issue: issue.number, url: issue.html_url, acceptedAt: c.created_at.slice(0, 10), ...report }); break; }
    }
  }
  reports.sort((a, b) => b.acceptedAt.localeCompare(a.acceptedAt) || b.issue - a.issue);
  writeFileSync(file, JSON.stringify({ formatVersion: 1, generatedAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z'), reports }));
  console.log(`${reports.length} accepted breach reports written to ${file}`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const [cmd, arg] = process.argv.slice(2);
  const run = { opened, accept, collect }[cmd];
  if (!run || !arg) { console.error('usage: breach-reports.mjs opened|accept <issue> | collect <file>'); process.exit(2); }
  run(cmd === 'collect' ? arg : Number(arg)).catch(e => { console.error(e); process.exit(1); });
}
