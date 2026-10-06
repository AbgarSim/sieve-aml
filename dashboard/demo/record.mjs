#!/usr/bin/env node
// Records the demo of the site as built tonight, so that it is always the current application:
// a browser walkthrough (video, GIF and screenshots) of the dashboard, REST calls against a
// running Vert.x server, one CLI screening, and demo/manifest.json, which the Demo page reads.
//
//   node demo/record.mjs --site http://localhost:4173/ [--api http://localhost:8080 | none]
//        [--cli sieve-cli/target/sieve-cli-0.1.0-SNAPSHOT.jar | none] [--out dist/demo]
//        [--api-timeout 600] [--commit SHA]
//
// The Dashboard workflow runs it after the site is built; `npm run demo` runs it locally against
// `vite preview`. The entity it screens is chosen from the data every time: the person listed on
// the most lists (OFAC SDN among them when possible), with the most relations.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { chromium } from 'playwright';

const opts = parseArgs(process.argv.slice(2));
const site = (opts.site ?? 'http://localhost:4173/').replace(/\/?$/, '/');
const api = opts.api && opts.api !== 'none' ? opts.api.replace(/\/$/, '') : null;
const cliJar = opts.cli && opts.cli !== 'none' ? opts.cli : null;
const out = path.resolve(opts.out ?? 'dist/demo');
const apiTimeout = Number(opts['api-timeout'] ?? 600);
const commit = opts.commit ?? process.env.GITHUB_SHA ?? gitHead();
const VP = { width: 1440, height: 900 };
const GIF_BUDGET = 4_000_000;

const stage = out + '.new';
fs.rmSync(stage, { recursive: true, force: true });
fs.mkdirSync(stage, { recursive: true });

const started = new Date();
const index = await json('data/search-index.json');
const relations = await json('data/relations.json').catch(() => ({ edges: [] }));
const overview = await json('data/overview.json');
const sources = await json('data/sources.json');
const entity = chooseEntity(index, relations);
if (!entity) throw new Error('The search index has no individual to screen');
const variant = misspell(entity.name);
const sourceName = id => sources.sources?.find(s => s.source === id)?.displayName ?? id;
console.log(`Entity: ${entity.name} (${entity.lists.join(', ')}; ${entity.links} links); misspelling: ${variant}`);

const cliRun = cliJar ? runCli(cliJar, entity.name) : null; // fetches OFAC SDN meanwhile
const walk = await walkthrough();
const rest = api ? await restCalls(api) : null;
const cli = cliRun ? await cliRun : null;

const manifest = {
  formatVersion: 1,
  generatedAt: started.toISOString(),
  commit: commit || undefined,
  site: process.env.SIEVE_SITE_URL || undefined,
  snapshot: { generatedAt: overview.generatedAt, totalEntities: overview.totalEntities, sources: overview.sourcesLoaded ?? overview.sourcesTotal, sample: overview.sample ?? false },
  entity: { name: entity.name, key: entity.key, lists: entity.lists, links: entity.links, url: `#/entity/${entity.source}/${encodeURIComponent(entity.id)}` },
  video: walk.video,
  gif: walk.gif,
  steps: walk.steps,
  rest,
  cli,
};
manifest.files = ['manifest.json', ...fs.readdirSync(stage).sort()];
fs.writeFileSync(path.join(stage, 'manifest.json'), JSON.stringify(manifest, null, 2));
fs.rmSync(out, { recursive: true, force: true });
fs.renameSync(stage, out);
summary(manifest);

// ---------------------------------------------------------------- walkthrough

async function walkthrough() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sieve-demo-'));
  const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined });
  const context = await browser.newContext({ viewport: VP, deviceScaleFactor: 1, recordVideo: { dir: tmp, size: VP } });
  await context.addInitScript(() => localStorage.setItem('sieve-theme', 'dark'));
  await context.addInitScript(overlayScript());
  const page = await context.newPage();
  const t0 = Date.now();
  const steps = [];
  const at = () => Math.round((Date.now() - t0) / 100) / 10;
  const pause = ms => page.waitForTimeout(ms);
  const say = text => page.evaluate(t => window.__demo?.caption(t), text);
  const shot = async name => {
    await page.evaluate(() => window.__demo?.hide(true));
    await page.screenshot({ path: path.join(stage, name) });
    await page.evaluate(() => window.__demo?.hide(false));
    return name;
  };
  const glide = async loc => {
    const b = await loc.boundingBox();
    if (b) await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 25 });
  };
  const scrollTo = loc => loc.evaluate(el => window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 76, behavior: 'smooth' }));
  const go = hash => page.evaluate(h => { window.scrollTo({ top: 0 }); location.hash = h; }, hash);
  const step = async (id, title, caption, fn) => {
    const s = { id, title, caption, at: at() };
    steps.push(s);
    await say(caption);
    s.screenshot = await fn();
  };

  await page.goto(site, { waitUntil: 'networkidle' });
  await page.waitForSelector('#overview .kpi');
  await page.mouse.move(720, 460);
  await pause(800);

  await step('overview', 'Overview', `${fmt(overview.totalEntities)} records from ${manifestSources()} lists, fetched and counted overnight`, async () => {
    await pause(2500);
    const shotName = await shot('overview.png');
    await scrollTo(page.locator('#map'));
    await pause(3500);
    await scrollTo(page.locator('#sources'));
    await pause(3000);
    return shotName;
  });

  await step('search', 'Screening a name', `Screening "${entity.name}": a typo-tolerant search over every published record`, async () => {
    await go('#/search');
    const box = page.locator('#big-q');
    await box.waitFor();
    await glide(box);
    await box.click();
    await pause(400);
    await box.pressSequentially(entity.name, { delay: 70 });
    const results = page.locator('.res a[href]');
    await results.first().waitFor({ timeout: 30000 });
    await pause(1500);
    const shotName = await shot('search.png');
    const own = entity.entries.map(e => `.res a[href="#/entity/${e.s}/${encodeURIComponent(e.id)}"]`).join(', ');
    const hit = (await page.locator(own).count()) ? page.locator(own).first() : results.first();
    await glide(hit);
    await pause(500);
    await hit.click();
    return shotName;
  });

  await step('entity', 'One profile across lists', `One profile across ${entity.lists.length} ${entity.lists.length === 1 ? 'list' : 'lists'}: properties, listings and relations merged`, async () => {
    await page.waitForSelector('.ehd h1');
    await page.waitForLoadState('networkidle');
    await pause(3000);
    const shotName = await shot('entity.png');
    for (const h of ['Sanctions and listings', 'Relations']) {
      const loc = page.locator(`h3:text-is("${h}")`);
      if (await loc.count()) { await scrollTo(loc.first()); await pause(3500); }
    }
    return shotName;
  });

  await step('graph', 'Association graph', 'The association graph: the links the lists state, drawn on a map', async () => {
    const card = page.locator('section.ag');
    await card.waitFor();
    await scrollTo(card);
    const nodes = page.locator('.ag-map svg g[role="button"]');
    await nodes.first().waitFor({ timeout: 20000 }).catch(() => {});
    await pause(2500);
    const shotName = await shot('graph.png');
    const n = await nodes.count();
    if (n > 0) {
      await glide(nodes.first());
      await pause(2000);
      if (n > 1) { await glide(nodes.nth(1)); await pause(1200); await nodes.nth(1).click(); await pause(2500); }
    }
    return shotName;
  });

  await step('source', 'Each list has its own page', `${sourceName(entity.source)}: publisher, coverage, programs and freshness`, async () => {
    await go(`#/source/${entity.source}`);
    await page.waitForSelector('.ehd h1');
    await pause(3000);
    return shot('source.png');
  });

  await step('light', 'Rebuilt every night', 'Rebuilt every night from the current code, in dark and light', async () => {
    await go('#/');
    await page.waitForSelector('#overview .kpi');
    await pause(1200);
    const toggle = page.locator('button[aria-label="Toggle theme"]');
    await glide(toggle);
    await toggle.click();
    await pause(2500);
    const shotName = await shot('overview-light.png');
    await say('');
    await pause(1000);
    return shotName;
  });

  const seconds = at();
  const recorded = await page.video().path();
  await context.close();
  await browser.close();

  const webm = path.join(stage, 'walkthrough.webm');
  fs.copyFileSync(recorded, webm);
  fs.rmSync(tmp, { recursive: true, force: true });
  ffmpeg(['-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '26', '-preset', 'veryfast', '-movflags', '+faststart', path.join(stage, 'walkthrough.mp4')]);
  ffmpeg(['-ss', '1.5', '-i', webm, '-frames:v', '1', '-q:v', '3', path.join(stage, 'poster.jpg')]);
  const duration = probeSeconds(webm) ?? seconds;
  const gif = makeGif(webm, steps);
  return {
    steps,
    video: { webm: 'walkthrough.webm', mp4: 'walkthrough.mp4', poster: 'poster.jpg', seconds: Math.round(duration * 10) / 10, width: VP.width, height: VP.height },
    gif,
  };
}

/** The GIF covers the search, profile and graph steps, encoded coarser until it fits the budget. */
function makeGif(webm, steps) {
  const from = Math.max(0, (steps.find(s => s.id === 'search')?.at ?? 0) - 0.3);
  const to = steps.find(s => s.id === 'source')?.at ?? steps.at(-1).at;
  const file = path.join(stage, 'walkthrough.gif');
  let bytes = 0;
  for (const [width, fps, colors] of [[720, 8, 128], [640, 6, 96], [560, 5, 64]]) {
    ffmpeg(['-ss', String(from), '-t', String(to - from), '-i', webm, '-vf',
      `fps=${fps},scale=${width}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=${colors}:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle`, file]);
    bytes = fs.statSync(file).size;
    if (bytes <= GIF_BUDGET) return { file: 'walkthrough.gif', bytes, from, to, width, fps };
  }
  return { file: 'walkthrough.gif', bytes, from, to, width: 560, fps: 5, overBudget: true };
}

/** A pointer and a caption bar drawn into the page, so the recording shows what is being done. */
function overlayScript() {
  return `(() => {
  const ready = () => {
    if (document.getElementById('demo-cursor')) return;
    const c = document.createElement('div');
    c.id = 'demo-cursor';
    c.style.cssText = 'position:fixed;left:-40px;top:-40px;width:24px;height:24px;margin:-2px 0 0 -3px;pointer-events:none;z-index:2147483647;filter:drop-shadow(0 1px 2px rgba(0,0,0,.4))';
    c.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24"><path d="M5 3l14 9-6.5 1.2L16 20l-2.6 1.1-3.4-6.6L5 19z" fill="#fff" stroke="#111" stroke-width="1.4" stroke-linejoin="round"/></svg>';
    const cap = document.createElement('div');
    cap.id = 'demo-caption';
    cap.style.cssText = 'position:fixed;left:50%;bottom:30px;transform:translateX(-50%);max-width:72%;padding:11px 20px;border-radius:12px;background:rgba(15,23,42,.9);color:#fff;font:500 18px/1.4 Inter,system-ui,sans-serif;letter-spacing:-.01em;z-index:2147483646;pointer-events:none;opacity:0;transition:opacity .3s;text-align:center';
    document.documentElement.append(c, cap);
    document.addEventListener('mousemove', e => { c.style.left = e.clientX + 'px'; c.style.top = e.clientY + 'px'; }, true);
    document.addEventListener('mousedown', e => {
      const r = document.createElement('div');
      r.style.cssText = 'position:fixed;width:36px;height:36px;border-radius:50%;border:3px solid #3b82f6;pointer-events:none;z-index:2147483646;transform:translate(-50%,-50%) scale(.3);opacity:.9;transition:transform .45s ease-out,opacity .45s ease-out;left:' + e.clientX + 'px;top:' + e.clientY + 'px';
      document.documentElement.append(r);
      requestAnimationFrame(() => { r.style.transform = 'translate(-50%,-50%) scale(1.3)'; r.style.opacity = '0'; });
      setTimeout(() => r.remove(), 500);
    }, true);
  };
  window.__demo = {
    caption(t) { ready(); const cap = document.getElementById('demo-caption'); cap.textContent = t || ''; cap.style.opacity = t ? '1' : '0'; },
    hide(h) { ready(); for (const id of ['demo-cursor', 'demo-caption']) document.getElementById(id).style.visibility = h ? 'hidden' : 'visible'; },
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready); else ready();
})();`;
}

// ---------------------------------------------------------------- REST and CLI

/** Waits for the Vert.x server to report a loaded index, then records a few calls as made. */
async function restCalls(base) {
  const begun = Date.now();
  let health = null;
  while (Date.now() - begun < apiTimeout * 1000) {
    try {
      const r = await fetch(`${base}/api/v1/health`);
      if (r.ok) { const j = await r.json(); if (j.index?.totalEntities > 0) { health = j; break; } }
    } catch { /* not up yet */ }
    await sleep(10000);
  }
  if (!health) return { base, note: `The server at ${base} did not report a loaded index within ${apiTimeout} seconds, so no REST calls were recorded tonight.`, calls: [] };
  const calls = [];
  const call = async (title, method, route, body) => {
    const t = Date.now();
    const r = await fetch(base + route, { method, headers: body ? { 'content-type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
    const text = await r.text();
    let pretty = text;
    try { pretty = JSON.stringify(JSON.parse(text), null, 2); } catch { /* not JSON */ }
    const lines = pretty.split('\n');
    calls.push({ title, method, path: route, request: body ?? null, status: r.status, ms: Date.now() - t,
      response: lines.slice(0, 80).join('\n'), omittedLines: Math.max(0, lines.length - 80) });
  };
  await call('Health: the index the server screens against', 'GET', '/api/v1/health');
  await call(`Screen "${entity.name}" as the lists spell it`, 'POST', '/api/v1/screen', { name: entity.name });
  await call(`Screen a misspelling, "${variant}", at threshold 0.85`, 'POST', '/api/v1/screen', { name: variant, threshold: 0.85 });
  await call('Screen a batch in one request', 'POST', '/api/v1/screen/batch', { requests: [{ name: entity.name }, { name: variant }, { name: 'Jane Example Nomatch' }] });
  return { base, loadedEntities: health.index.totalEntities, calls };
}

/** Runs `sieve screen` in the background (it fetches OFAC SDN first) and records its output. */
function runCli(jar, name) {
  return new Promise(resolve => {
    const t = Date.now();
    const p = spawn('java', ['-Xmx2g', '-jar', jar, 'screen', name], { env: { ...process.env, NO_COLOR: '1' } });
    let stdout = '', stderr = '';
    p.stdout.on('data', d => { stdout += d; });
    p.stderr.on('data', d => { stderr += d; });
    const timer = setTimeout(() => p.kill('SIGKILL'), 15 * 60 * 1000);
    p.on('close', code => {
      clearTimeout(timer);
      resolve({ command: `java -jar ${path.basename(jar)} screen "${name}"`, exitCode: code, ms: Date.now() - t,
        output: tail(stdout, 60), stderr: tail(stderr, 12) || undefined,
        exitCodes: '0 no match, 1 match found, 2 error' });
    });
    p.on('error', e => { clearTimeout(timer); resolve({ command: `java -jar ${path.basename(jar)} screen "${name}"`, exitCode: null, ms: Date.now() - t, output: '', stderr: String(e), exitCodes: '0 no match, 1 match found, 2 error' }); });
  });
}

// ---------------------------------------------------------------- choosing what to show

/**
 * The person to screen: on the most lists (OFAC SDN among them when any such person exists),
 * then with the most relations, then by name; a name that can be typed in the recording.
 */
function chooseEntity(ix, rel) {
  const groups = new Map();
  for (const e of ix.entries ?? []) {
    const g = e.g ?? e.k;
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(e);
  }
  const links = new Map();
  for (const ed of rel.edges ?? []) for (const k of [ed.f, ed.t]) links.set(k, (links.get(k) ?? 0) + 1);
  let best = null;
  for (const entries of groups.values()) {
    if (entries[0].t !== 'I') continue;
    const primary = entries.find(e => e.s === 'OFAC_SDN') ?? entries[0];
    const name = primary.n;
    if (!/^[\p{L}\p{M}' .,-]{4,40}$/u.test(name)) continue;
    const cand = {
      name, key: primary.k, source: primary.s, id: primary.k.slice(primary.k.indexOf('/') + 1), entries,
      lists: [...new Set(entries.map(e => e.s))].sort(),
      links: entries.reduce((n, e) => n + (links.get(e.k) ?? 0), 0),
      ofac: entries.some(e => e.s === 'OFAC_SDN') ? 1 : 0,
    };
    if (!best || better(cand, best)) best = cand;
  }
  return best;
}
function better(a, b) {
  return a.ofac !== b.ofac ? a.ofac > b.ofac
    : a.lists.length !== b.lists.length ? a.lists.length > b.lists.length
    : a.links !== b.links ? a.links > b.links
    : a.name.localeCompare(b.name) < 0;
}

/** A plausible misspelling: one vowel changed in the longest word, the last letter of another dropped. */
function misspell(name) {
  const words = name.split(' ');
  const longest = words.reduce((a, b) => (b.length > a.length ? b : a));
  const swap = { a: 'e', e: 'i', i: 'y', o: 'u', u: 'o', y: 'i' };
  const i = Math.max(...Object.keys(swap).map(v => longest.toLowerCase().lastIndexOf(v)));
  const changed = i >= 0 ? longest.slice(0, i) + swap[longest[i].toLowerCase()] + longest.slice(i + 1) : longest + 'e';
  let dropped = false;
  return words.map(w => (w === longest ? changed : !dropped && w.length > 4 ? ((dropped = true), w.slice(0, -1)) : w)).join(' ');
}

// ---------------------------------------------------------------- helpers

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) o[argv[i].slice(2)] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : 'true';
  return o;
}
async function json(file) {
  const r = await fetch(site + file);
  if (!r.ok) throw new Error(`${site}${file}: HTTP ${r.status}`);
  return r.json();
}
function manifestSources() { return overview.sourcesLoaded ?? sources.sources?.length ?? 0; }
function gitHead() { const r = spawnSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }); return r.status === 0 ? r.stdout.trim() : ''; }
function ffmpeg(args) {
  const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', ...args], { encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`ffmpeg ${args.join(' ')}: ${r.stderr || r.error}`);
}
function probeSeconds(file) {
  const r = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file], { encoding: 'utf8' });
  const n = Number(r.stdout);
  return r.status === 0 && n > 0 ? n : null;
}
function fmt(n) { return Number(n).toLocaleString('en-US'); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function tail(s, n) { const lines = s.trimEnd().split('\n'); return (lines.length > n ? [`… (${lines.length - n} earlier lines omitted)`, ...lines.slice(-n)] : lines).join('\n'); }
function summary(m) {
  const lines = [
    `Demo written to ${out} (${m.files.length} files)`,
    `- Walkthrough: ${m.video.seconds} s, ${m.steps.length} steps, entity "${m.entity.name}" on ${m.entity.lists.length} lists`,
    `- GIF: ${(m.gif.bytes / 1e6).toFixed(1)} MB, ${m.gif.width}px at ${m.gif.fps} fps${m.gif.overBudget ? ' (over budget)' : ''}`,
    `- REST: ${m.rest ? (m.rest.calls.length ? `${m.rest.calls.length} calls against ${fmt(m.rest.loadedEntities)} entities` : m.rest.note) : 'skipped'}`,
    `- CLI: ${m.cli ? `exit ${m.cli.exitCode} after ${Math.round(m.cli.ms / 1000)} s` : 'skipped'}`,
  ];
  console.log(lines.join('\n'));
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `### Demo\n${lines.slice(1).join('\n')}\n`);
}
