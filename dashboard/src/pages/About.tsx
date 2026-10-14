import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { HBar } from '../components/HBar';
import { StatTile } from '../components/StatTile';
import { ChangeList } from '../components/ChangeRows';
import { Icon } from '../lib/icons';
import { SIEVE } from '../data/snapshot';
import { nightsOf, useChanges } from '../data/changes';
import { KIND_LABEL as ACTION_LABEL, loadEnforcement, money, type RawEnforcement } from '../data/enforcement';
import { fmt, ms } from '../lib/format';

/** Portrait shown in the author card; the initials stand in until the file is there. */
const PHOTO = 'about/abgar-simonean.jpg';
const FORMAT_NAME: Record<string, string> = { XML: 'XML', JSON: 'JSON', CSV: 'CSV', XLSX: 'spreadsheets', HTML: 'web pages', PDF: 'PDF' };
const list = (xs: string[]) => (xs.length < 2 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`);

/** One chapter of the story: a numbered eyebrow, a heading, prose on the left and what the data shows on the right. */
function Chapter({ n, eyebrow, title, children, aside, id }: { n: string; eyebrow: string; title: string; children: ReactNode; aside?: ReactNode; id?: string }) {
  return (
    <section className={'chapter' + (aside ? ' with-aside' : '')} id={id} aria-labelledby={id ? `${id}-h` : undefined}>
      <div className="chapter-text">
        <div className="eyebrow num">{n} · {eyebrow}</div>
        <h2 id={id ? `${id}-h` : undefined}>{title}</h2>
        <div className="prose">{children}</div>
      </div>
      {aside && <div className="chapter-data">{aside}</div>}
    </section>
  );
}

const Skeleton = ({ h = 120 }: { h?: number }) => <div className="skl" style={{ height: h }} />;
const More = ({ to, children }: { to: string; children: ReactNode }) => <Link to={to} className="more-link cl">{children} <Icon name="right" size={14} /></Link>;

export default function About() {
  const D = SIEVE, S = D.snapshot;
  const nav = useNavigate();
  const q = useRef<HTMLInputElement>(null);
  const [photo, setPhoto] = useState(true);
  const { changes } = useChanges();
  const [enf, setEnf] = useState<RawEnforcement | null | undefined>(undefined);
  useEffect(() => { document.title = 'Sieve — Sanctions screening'; let live = true; loadEnforcement().then(e => live && setEnf(e), () => live && setEnf(null)); return () => { live = false; }; }, []);
  const screen = (e: FormEvent) => { e.preventDefault(); const v = q.current?.value.trim(); nav(v ? '/search?q=' + encodeURIComponent(v) : '/search'); };

  // What the lists gave tonight
  const online = D.sources.filter(s => s.status === 'loaded');
  const authorities = new Set(D.sources.map(s => s.authority)).size;
  const formats = [...new Set(D.sources.map(s => FORMAT_NAME[s.format] ?? s.format))];
  const largest = [...D.sources].filter(s => s.published > 0).sort((a, b) => b.published - a.published).slice(0, 6);
  const topCountries = [...D.countries].sort((a, b) => b.total - a.total).slice(0, 6);
  const programs = D.programs.slice(0, 3);
  const night = nightsOf(changes)[0];
  const newest = (night?.records?.added ?? []).slice(0, 5);

  // Enforcement, when the snapshot has it
  const actions = enf?.actions ?? [];
  const yearAgo = new Date(); yearAgo.setUTCFullYear(yearAgo.getUTCFullYear() - 1);
  const lastYear = actions.filter(a => a.date >= yearAgo.toISOString().slice(0, 10)).length;
  const sum = (cur: string) => actions.filter(a => a.currency === cur && a.kind === 'CIVIL_PENALTY').reduce((s, a) => s + (a.amount ?? 0), 0);
  const usd = sum('USD'), gbp = sum('GBP');
  const latest = actions.slice(0, 3);

  return (
    <>
      <Header active="About" hideSearch />
      <main className="wrap page about">
        <section className="about-hero">
          <h1>An open-source sanctions screening project</h1>
          <p>Sieve fetches {D.sources.length} official sanctions and watch lists every night, combines them into one searchable database of {fmt(D.totalEntities)} records and screens names against it with typo tolerance. The code, the data pipeline and this site are open source under the MIT licence.</p>
          <form className="bigsearch about-search" role="search" onSubmit={screen}>
            <Icon name="search" size={20} /><input id="big-q" ref={q} placeholder={`Screen a name against ${online.length} lists…`} autoComplete="off" aria-label="Screen a name" /><button type="submit" className="btn pri sm">Screen</button>
          </form>
          <div className="about-cta">
            <Link className="btn" to="/overview">Open the overview</Link>
            <Link className="btn" to="/changes">What changed tonight</Link>
            <a className="btn" href="https://github.com/AbgarSim/sieve-aml" target="_blank" rel="noopener">Source code on GitHub</a>
          </div>
        </section>

        <div className="story">
          <Chapter n="01" eyebrow="Who I am" title="Hi, I'm Abgar." id="me" aside={
            <div className="about-photo-card">
              <div className="about-photo">{photo ? <img src={PHOTO} alt="Abgar Simonean" onError={() => setPhoto(false)} /> : <span aria-hidden="true">AS</span>}</div>
              <div><b style={{ color: 'var(--heading)' }}>Abgar Simonean</b><div className="small muted">Author of Sieve</div></div>
            </div>}>
            <p>I have spent much of my career in finance, building financial applications for many years. That work took me to central bank applications and settlement engines, the systems that move money between banks, and from there to sanctions screening and AML compliance projects, where payments and customers are checked against lists like the ones on this site.</p>
            <p>I am concerned about sanctions hygiene in the current landscape of the world. The lists are published by many authorities, each in its own format, and they change every day; a screening is only as good as the data behind it and the matching on top of it, and most of that work happens where nobody can look at it.</p>
            <p>So I built Sieve as an open-source sanctions project, so that anyone can run it, inspect it and improve it. It reads every list in the list's own format, matches the same person or company across lists, screens names with tolerance for spelling and script, and publishes what it finds on this site every night. The rest of this page is what it found tonight.</p>
          </Chapter>

          <Chapter n="02" eyebrow="Every night" title={`${D.sources.length} lists, read as one database`} id="lists" aside={
            <div className="chapter-grid">
              <div className="card"><div className="card-h"><h3>Largest lists</h3><span className="xs muted">records published</span></div><div className="card-b">{largest.map(s => <HBar key={s.id} label={<Link to={`/source/${s.id}`} className="cl"><Flag cc={s.cc} />{s.name}</Link>} pct={(s.published / largest[0].published) * 100} value={fmt(s.published)} />)}</div></div>
              <div className="card"><div className="card-h"><h3>Most listed countries</h3><span className="xs muted">by nationality or address</span></div><div className="card-b">{topCountries.map(c => <HBar key={c.cc} label={<Link to={`/search?country=${c.cc}`} className="cl"><Flag cc={c.cc} />{c.name}</Link>} pct={(c.total / topCountries[0].total) * 100} value={fmt(c.total)} />)}</div></div>
            </div>}>
            <p>At three in the morning, UTC, Sieve fetches {D.sources.length} official lists from {authorities} authorities: {list(formats)}. It reads each one into the same schema, resolves the countries, scripts and identifiers the lists write in their own ways, and matches the records that describe one person or company across lists, so that a name is screened once against all of them.</p>
            <p>Tonight that gave <b>{fmt(D.totalEntities)}</b> records from {online.length} lists, {fmt(D.totalNames)} screenable names including aliases{D.distinctEntities != null && <>, and <b>{fmt(D.distinctEntities)}</b> distinct entities once the same ones are matched across lists{D.onSeveralLists ? `, ${fmt(D.onSeveralLists)} of them listed by more than one authority` : ''}</>}. The records are linked to {fmt(S.countries)} countries{programs.length ? <>, and the largest programs are {list(programs.map(p => p.code))}</> : null}.{S.ingestMs != null && <> The whole fetch takes {ms(S.ingestMs)}, because the lists are read in parallel.</>}</p>
            <div className="grid g4 about-tiles">
              <StatTile label="Lists online" value={<>{S.sourcesLoaded}<small>/{S.sourcesTotal}</small></>} note="fetched tonight" />
              <StatTile label="Records" value={fmt(D.totalEntities)} note="searchable, with a profile each" />
              <StatTile label="Distinct entities" value={D.distinctEntities == null ? '—' : fmt(D.distinctEntities)} note="matched across lists" />
              <StatTile label="Countries" value={fmt(S.countries)} note="by nationality or address" />
            </div>
            <More to="/overview">See the overview: map, composition and data quality</More>
          </Chapter>

          <Chapter n="03" eyebrow="What changed tonight" title={night ? `${fmt(night.added)} new, ${fmt(night.removed)} delisted, ${fmt(night.changed)} changed` : 'New listings, delistings and changes'} id="tonight" aside={
            changes === undefined ? <Skeleton /> : night ? (
              <div className="card">
                <div className="card-h"><h3>Newest listings</h3><span className="xs muted num">{night.date}</span></div>
                <ChangeList rows={newest} total={night.added} link shown={5} compact />
              </div>
            ) : null}>
            <p>Lists change every day: authorities designate new names, remove others and correct the records they already hold. Every night Sieve compares what each list published with what it published the night before and keeps the difference for 30 nights.</p>
            {changes === undefined ? <Skeleton h={48} /> : night ? (
              <div className="grid g3 about-tiles">
                <StatTile label={KIND_LABEL.added} value={<span className={night.added ? 'pos' : ''}>+{fmt(night.added)}</span>} note={`first seen ${night.date}`} />
                <StatTile label={KIND_LABEL.removed} value={<span className={night.removed ? 'neg' : ''}>−{fmt(night.removed)}</span>} note="gone from a list that loaded" />
                <StatTile label={KIND_LABEL.changed} value={fmt(night.changed)} note="records with a changed field" />
              </div>
            ) : <p className="muted">The change log starts with the next nightly run.</p>}
            <More to="/changes">See every night, list by list</More>
          </Chapter>

          <Chapter n="04" eyebrow="Enforcement" title="Watching who is penalised for breaking sanctions" id="enforcement" aside={
            enf === undefined ? <Skeleton /> : latest.length ? (
              <div className="card">
                <div className="card-h"><h3>Latest actions</h3><span className="xs muted">as published</span></div>
                <ul className="chg-list">{latest.map(a => (
                  <li key={a.id}>
                    <span className="num xs muted" style={{ flex: 'none' }}>{a.date}</span>
                    <span className="nm"><a href={a.url} target="_blank" rel="noopener noreferrer">{a.party ?? a.summary ?? a.source}</a></span>
                    <span className="xs muted">{a.source === 'FINCEN' ? 'FinCEN' : a.source} · {ACTION_LABEL[a.kind]}</span>
                    {a.amount != null && <span className="num small" style={{ marginLeft: 'auto' }}>{money(a.amount, a.currency)}</span>}
                  </li>))}
                </ul>
              </div>
            ) : null}>
            <p>Listing is one half of sanctions; enforcement is the other. Sieve follows the civil penalties, disclosures and criminal cases that OFAC, OFSI, FinCEN and the US Department of Justice publish for sanctions breaches, reads the programs each one breached, and places it on the country those programs target. News reports of suspected breaches are kept apart from the official record and marked unverified.</p>
            {enf === undefined ? <Skeleton h={48} /> : enf ? (
              <div className="grid g4 about-tiles">
                <StatTile label="Actions" value={fmt(actions.length)} note="published enforcement actions" />
                <StatTile label="Last 12 months" value={fmt(lastYear)} note="published in the past year" />
                <StatTile label="US civil penalties" value={usd ? money(usd, 'USD') : '—'} note="OFAC and FinCEN, in USD" />
                <StatTile label="UK penalties" value={gbp ? money(gbp, 'GBP') : '—'} note="OFSI, total in GBP" />
              </div>
            ) : <p className="muted">Enforcement data arrives with the next nightly run.</p>}
            <More to="/monitoring">Open the monitoring map</More>
          </Chapter>

          <Chapter n="05" eyebrow="Open source" title="Run it, read it, improve it" id="open">
            <p>Everything on this site comes from the public repository: the loaders for every list, the matching, the nightly pipeline that rebuilds this database, the screening API and the site itself, under the MIT licence. Sieve is a screening tool, not legal advice; each authority's own publication is authoritative.</p>
            <div className="about-cta" style={{ justifyContent: 'flex-start', marginTop: 16 }}>
              <Link className="btn pri" to="/search">Screen a name</Link>
              <a className="btn" href="https://github.com/AbgarSim/sieve-aml" target="_blank" rel="noopener">Source code on GitHub</a>
            </div>
          </Chapter>
        </div>
      </main>
      <Footer />
    </>
  );
}

const KIND_LABEL = { added: 'New listings', removed: 'Delisted', changed: 'Changed' };
