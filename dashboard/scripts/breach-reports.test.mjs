import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MARKER, acceptedComment, frozen, links, parseForm, toReport } from './breach-reports.mjs';

const BODY = `### Who is suspected

Example Shipping Ltd

### Sanctions programs

Russia, Iran, Other

### When

_No response_

### Where

Port of Example

### What happened

Two articles report that the company's tanker loaded Russian crude above the price cap.

### Public sources

https://news.example.org/a-story.
see also (https://court.example.gov/case/1)
https://news.example.org/a-story

### Before you submit

- [X] Everything in this report comes from the public sources I linked.
- [X] I understand this issue is public.`;

test('shouldReadEveryFieldWhenTheBodyIsAnIssueForm', () => {
  const f = parseForm(BODY);
  assert.equal(f.party, 'Example Shipping Ltd');
  assert.equal(f.programs, 'Russia, Iran, Other');
  assert.equal(f.when, '');
  assert.equal(f.where, 'Port of Example');
});

test('shouldKeepDistinctLinksWithoutTrailingPunctuationWhenSourcesAreListed', () => {
  assert.deepEqual(links(parseForm(BODY).sources), ['https://news.example.org/a-story', 'https://court.example.gov/case/1']);
  assert.deepEqual(links('javascript:alert(1) ftp://x.org'), []);
});

test('shouldMapProgramsToTargetedCountriesWhenTheReportIsComplete', () => {
  const { report, problems } = toReport(parseForm(BODY));
  assert.deepEqual(problems, []);
  assert.deepEqual(report.programs, ['Russia', 'Iran']);
  assert.deepEqual(report.targets, ['RU', 'IR']);
  assert.equal(report.otherProgram, true);
  assert.equal(report.when, undefined);
});

test('shouldListProblemsWhenRequiredFieldsAreMissing', () => {
  const { report, problems } = toReport(parseForm('### Who is suspected\n\n_No response_\n\n### Public sources\n\nno links here'));
  assert.equal(report, undefined);
  assert.equal(problems.length, 4);
});

test('shouldReadTheFrozenReportOnlyWhenTheBotWroteIt', () => {
  const { report } = toReport(parseForm(BODY));
  const body = acceptedComment(report);
  assert.ok(body.startsWith(MARKER));
  assert.deepEqual(frozen({ user: { login: 'github-actions[bot]' }, body }), report);
  assert.equal(frozen({ user: { login: 'someone' }, body }), null);
  assert.equal(frozen({ user: { login: 'github-actions[bot]' }, body: body.replace('"party"', '"nobody"') }), null);
});
