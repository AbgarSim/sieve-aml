import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Chip } from '../components/Badges';
import { Icon } from '../lib/icons';
import { fmt } from '../lib/format';

/** What demo/record.mjs writes beside the site every night. */
interface Step { id: string; title: string; caption: string; at: number; screenshot?: string }
interface Call { title: string; method: string; path: string; request: unknown; status: number; ms: number; response: string; omittedLines: number }
interface Manifest {
  formatVersion: number; generatedAt: string; commit?: string;
  snapshot: { generatedAt: string; totalEntities: number; sources: number; sample?: boolean };
  entity: { name: string; key: string; lists: string[]; links: number; url: string };
  video: { webm: string; mp4: string; poster: string; seconds: number; width: number; height: number };
  gif: { file: string; bytes: number; overBudget?: boolean };
  steps: Step[];
  rest: { base: string; loadedEntities?: number; note?: string; calls: Call[] } | null;
  cli: { command: string; exitCode: number | null; ms: number; output: string; stderr?: string; exitCodes: string } | null;
}

const DEMO = 'demo/';
const day = (iso: string) => iso.slice(0, 10);
const clock = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
/** Only a manifest of the shape this page renders; an older or partial one shows the empty state rather than a blank page. */
const valid = (j: unknown): j is Manifest => {
  const m = j as Partial<Manifest> | null;
  return !!m && m.formatVersion === 1 && !!m.video && !!m.gif && !!m.entity && Array.isArray(m.steps);
};

/** The walkthrough recorded from tonight's build: video, the steps it took, and the REST and CLI calls it made. */
export default function Demo() {
  const [m, setM] = useState<Manifest | null>();
  const video = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    document.title = 'Demo — Sieve';
    fetch(DEMO + 'manifest.json').then(r => (r.ok ? (r.json() as Promise<unknown>) : null)).then(j => setM(valid(j) ? j : null), () => setM(null));
  }, []);
  const seek = (s: number) => {
    const v = video.current;
    if (!v) return;
    v.currentTime = s;
    v.play().catch(() => {});
    v.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };
  return (
    <>
      <Header active="Demo" />
      <main className="wrap page">
        <div className="blk-h">
          <h1>Demo</h1>
          {m && <p>Recorded {day(m.generatedAt)} from {m.commit ? <a className="num" href={`https://github.com/AbgarSim/sieve-aml/commit/${m.commit}`} target="_blank" rel="noopener">commit {m.commit.slice(0, 7)}</a> : 'the current code'} against the snapshot of {day(m.snapshot.generatedAt)}</p>}
        </div>
        {m === undefined && <div className="card"><div className="card-b"><div className="skl" style={{ height: 420 }} /></div></div>}
        {m === null && (
          <div className="card empty"><Icon name="warn" size={36} /><h3>No demo has been recorded for this build</h3>
            <p>The nightly Dashboard workflow records one after it builds the site. Locally, run <span className="num">npm run demo</span> against <span className="num">npm run preview</span>.</p></div>
        )}
        {m && (
          <div className="grid">
            <p className="demo-lead">Nothing here is staged. Every night the dashboard is rebuilt from the current code and the night's lists, and a script then drives the result in a browser, records what it sees, and calls the REST server and the CLI the same way an integration would. What you see below is that recording; when a night's recording fails, the previous one stays up.</p>
            <div className="card">
              <video ref={video} className="demo-video" controls preload="metadata" playsInline poster={DEMO + m.video.poster} width={m.video.width} height={m.video.height}>
                <source src={DEMO + m.video.mp4} type="video/mp4" />
                <source src={DEMO + m.video.webm} type="video/webm" />
              </video>
              <div className="card-f demo-foot">
                <span className="small muted">{clock(m.video.seconds)} · {m.video.width}×{m.video.height} · <a href={DEMO + m.video.mp4} download>MP4</a> · <a href={DEMO + m.video.webm} download>WebM</a>{!m.gif.overBudget && <> · <a href={DEMO + m.gif.file} download>GIF</a></>}</span>
                <span className="small muted">Screened: <Link to={m.entity.url.replace(/^#/, '')}>{m.entity.name}</Link>, on {m.entity.lists.length} {m.entity.lists.length === 1 ? 'list' : 'lists'}, chosen as {m.entity.lists.includes('OFAC_SDN') ? 'the OFAC-listed person on the most lists' : 'the person on the most lists'}</span>
              </div>
            </div>
            <div className="demo-steps">
              {m.steps.map(s => (
                <button key={s.id} className="demo-step" onClick={() => seek(s.at)} title={`Play from ${clock(s.at)}`}>
                  {s.screenshot && <img src={DEMO + s.screenshot} alt="" loading="lazy" />}
                  <div><div className="cl" style={{ justifyContent: 'space-between' }}><b>{s.title}</b><span className="num xs muted">{clock(s.at)}</span></div><span className="small muted">{s.caption}</span></div>
                </button>
              ))}
            </div>
            <div className="card">
              <div className="card-h"><h3>REST API, as called for this recording</h3>{m.rest?.loadedEntities != null && <span className="xs muted">Vert.x server with {fmt(m.rest.loadedEntities)} entities loaded</span>}</div>
              {!m.rest || m.rest.calls.length === 0
                ? <div className="card-b small muted">{m.rest?.note ?? 'The REST calls were not recorded.'}</div>
                : m.rest.calls.map((c, i) => (
                  <div key={i} className="demo-call">
                    <div className="h"><Chip accent>{c.method}</Chip><span className="num">{c.path}</span><span className="xs muted">HTTP {c.status} · {c.ms} ms</span><span className="small" style={{ flexBasis: '100%' }}>{c.title}</span></div>
                    {c.request != null && <pre className="demo-pre">{JSON.stringify(c.request, null, 2)}</pre>}
                    <pre className="demo-pre">{c.response}{c.omittedLines > 0 && `\n… ${c.omittedLines} more lines`}</pre>
                  </div>
                ))}
            </div>
            <div className="card">
              <div className="card-h"><h3>Command line, as run for this recording</h3>{m.cli && <span className="xs muted">exit code {m.cli.exitCode ?? '—'} · {m.cli.exitCodes}</span>}</div>
              {!m.cli
                ? <div className="card-b small muted">The CLI run was not recorded.</div>
                : <div className="card-b"><pre className="demo-pre">$ {m.cli.command}{'\n'}{m.cli.output}{m.cli.stderr && `\n${m.cli.stderr}`}</pre></div>}
            </div>
          </div>
        )}
      </main>
      <Footer />
    </>
  );
}
