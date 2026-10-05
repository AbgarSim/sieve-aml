import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { Badge, Chip, TopicBadge, TopicBadges, TypeBadge } from '../components/Badges';
import { Icon, TYPE_ICON } from '../lib/icons';
import { RAW_TYPE, SIEVE, TYPE_LABEL, byTopicOrder, type Source } from '../data/snapshot';
import { loadGroup, type Entry, type Index } from '../data/search';
import type { RawEntity, RawImage, RawLink, RawLinkKind, RawName } from '../data/raw';
import { useIndex } from '../data/useIndex';
import { countryName } from '../data/iso';
import { host } from '../lib/format';
import { normalize } from '../lib/jw';
import { useToast } from '../lib/useToast';
import { entityPath } from './Search';
import { AssociationGraph } from '../components/AssociationGraph';
import { loadNews, type NewsArticle } from '../data/news';

const SCRIPTS = ['LATIN', 'CYRILLIC', 'ARABIC', 'CJK', 'OTHER'];
const SCRIPT_LABEL: Record<string, string> = { LATIN: 'Latin', CYRILLIC: 'Cyrillic', ARABIC: 'Arabic', CJK: 'CJK', OTHER: 'Other script' };
const NAME_TYPE: Record<string, string> = { AKA: 'a.k.a.', FKA: 'f.k.a.', MAIDEN: 'maiden name', PRIMARY: 'primary name' };
const GENDER: Record<string, string> = { MALE: 'Male', FEMALE: 'Female', OTHER: 'Other' };
const ID_TYPE: Record<string, string> = { PASSPORT: 'Passport', NATIONAL_ID: 'National ID', TAX_ID: 'Tax ID', IMO_NUMBER: 'IMO number', MMSI: 'MMSI', REGISTRATION_NUMBER: 'Registration number', SWIFT_BIC: 'SWIFT/BIC', LEI: 'LEI', BUSINESS_REGISTRATION: 'Business registration', OTHER: 'Other' };
/** How a relation reads from the holder's side and from the target's side. */
const REL: Record<string, [string, string]> = {
  OWNERSHIP: ['Owns', 'Owned by'], DIRECTORSHIP: ['Director of', 'Director'], FAMILY: ['Family', 'Family'],
  ASSOCIATE: ['Associate', 'Associate'], LINKED: ['Linked to', 'Linked to'], POSITION_HELD: ['Position held', 'Held by'],
};
/** Link groups in the order they are shown, with how each reads as a heading. */
const LINK_KINDS: [RawLinkKind, string][] = [['SOURCE_PAGE', 'Listing pages'], ['LEGAL_ACT', 'Legal acts'], ['ENCYCLOPEDIA', 'Reference'], ['WEBSITE', 'Website']];
const tons = (n: number) => n.toLocaleString('en-US');
const day = (iso?: string) => (iso ? iso.slice(0, 10) : undefined);
const address = (a: NonNullable<RawEntity['addresses']>[number]) => a.fullAddress || [a.street, a.city, a.stateOrProvince, a.postalCode, a.country].filter(Boolean).join(', ');
const minOf = (xs: (string | undefined)[]) => xs.filter((x): x is string => !!x).sort()[0];
const maxOf = (xs: (string | undefined)[]) => xs.filter((x): x is string => !!x).sort().pop();
const list = (xs: string[]) => (xs.length < 2 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`);

/** One record of the entity: the list's index entry, its full record and its reference number on the page. */
interface Rec { e: Entry; r: RawEntity; n: number; src?: Source }
/** A value stated by one or more records, with the reference numbers of those records. */
interface Sourced<T> { v: T; refs: number[] }

/** Merges the values every record states, keeping the first spelling of each and noting which records state it. */
function collect<T>(recs: Rec[], get: (r: RawEntity) => T[] | undefined, key: (v: T) => string): Sourced<T>[] {
  const out = new Map<string, Sourced<T>>();
  for (const rec of recs) for (const v of get(rec.r) ?? []) {
    const k = key(v); if (!k) continue;
    const s = out.get(k);
    if (!s) out.set(k, { v, refs: [rec.n] }); else if (!s.refs.includes(rec.n)) s.refs.push(rec.n);
  }
  return [...out.values()];
}

const goTo = (n: number) => document.getElementById(`src-${n}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
/** Reference numbers of the sources stating a value, left out when every source states it; each one scrolls to that source's section. */
function Refs({ refs, recs }: { refs: number[]; recs: Rec[] }) {
  if (recs.length < 2 || refs.length === recs.length) return null;
  return <span className="refs">{refs.map(n => <button key={n} onClick={() => goTo(n)} title={recs[n - 1].src?.name ?? recs[n - 1].e.source}>{n}</button>)}</span>;
}

function Shell({ children }: { children: ReactNode }) {
  return <><Header active="Search" /><main className="wrap page">{children}</main><Footer /></>;
}

export default function Entity() {
  const { source = '', id = '' } = useParams();
  const key = `${source}/${id}`;
  const { ix, error } = useIndex();
  const [recs, setRecs] = useState<Rec[] | null | undefined>(undefined);
  const entry: Entry | undefined = ix?.byKey.get(key);
  useEffect(() => {
    if (!ix) return;
    if (!entry) { setRecs(null); return; }
    let live = true; setRecs(undefined);
    loadGroup(ix, entry).then(
      g => live && setRecs(g.length ? g.map((x, i) => ({ ...x, n: i + 1, src: SIEVE.byId[x.e.source] })) : null),
      () => live && setRecs(null));
    return () => { live = false; };
  }, [ix, entry]);
  const toast = useToast();

  if (error) return <Shell><div className="card empty"><Icon name="warn" size={36} /><h3>Snapshot unavailable</h3><p>{error}</p></div></Shell>;
  if (!ix || recs === undefined) return <Shell><div className="card" style={{ padding: 24, display: 'grid', gap: 12 }}><div className="skl" style={{ height: 28, width: '40%' }} /><div className="skl" style={{ height: 14, width: '25%' }} /><div className="skl" style={{ height: 180 }} /></div></Shell>;
  if (!entry || !recs) return <Shell><div className="card empty"><Icon name="warn" size={36} /><h3>No record {key}</h3><p>It is not in the latest snapshot; it may have been delisted. <Link to="/search">Back to search</Link></p></div></Shell>;
  return <Shell><Profile ix={ix} entry={entry} recs={recs} toast={toast} /></Shell>;
}

function Profile({ ix, entry, recs, toast }: { ix: Index; entry: Entry; recs: Rec[]; toast: ReturnType<typeof useToast> }) {
  const [news, setNews] = useState<{ days?: number; records: Record<string, NewsArticle[]> }>({ records: {} });
  useEffect(() => {
    let live = true;
    loadNews().then(n => live && setNews({ days: n.days, records: n.records ?? {} }), () => {});
    return () => { live = false; };
  }, []);
  const D = SIEVE, S = D.snapshot;
  const here = recs.find(x => x.e.key === entry.key) ?? recs[0];
  const rec = here.r, type = RAW_TYPE[rec.entityType], isPerson = type === 'individual';
  const name = rec.primaryName.fullName;
  document.title = `${name} — Sieve`;

  const topics = [...new Set(recs.flatMap(x => x.r.topics ?? x.e.topics))].sort(byTopicOrder);
  const names = collect<RawName>(recs, r => [r.primaryName, ...(r.aliases ?? [])], n => normalize(n.fullName)).filter(n => normalize(n.v.fullName) !== normalize(name));
  const byScript = SCRIPTS.map(s => [s, names.filter(a => (a.v.script ?? 'LATIN') === s)] as const).filter(x => x[1].length);
  const dobs = collect(recs, r => r.datesOfBirth, d => d);
  const pobs = collect(recs, r => r.placesOfBirth, p => p.toLowerCase());
  const genders = collect(recs, r => (r.gender ? [r.gender] : []), g => g);
  const deceased = collect(recs, r => (r.deceased ? ['yes'] : []), x => x);
  const nats = collect(recs, r => r.nationalities, c => c.toLowerCase());
  const cits = collect(recs, r => r.citizenships, c => c.toLowerCase()).filter(c => !nats.some(n => n.v.toLowerCase() === c.v.toLowerCase()));
  const countries = [...new Set(recs.flatMap(x => x.e.countries))]; // from the index, which holds resolved ISO codes
  const addrs = collect(recs, r => (r.addresses ?? []).map(address), a => a.toLowerCase().replace(/[\s,.]+/g, ' ').trim());
  const ids = collect(recs, r => r.identifiers, i => `${i.type}:${i.value.replace(/[\s-]/g, '').toUpperCase()}`);
  const vessel = (k: keyof NonNullable<RawEntity['vessel']>) => collect(recs, r => (r.vessel?.[k] != null ? [String(r.vessel[k])] : []), v => v);
  const vesselRows: [string, Sourced<string>[], boolean][] = [['Flag', vessel('flag'), false], ['Vessel type', vessel('type'), false], ['Call sign', vessel('callSign'), true], ['Tonnage', vessel('tonnage'), true], ['Gross registered tonnage', vessel('grossRegisteredTonnage'), true]];
  type Program = NonNullable<RawEntity['programs']>[number];
  const listings = recs.flatMap((x): { x: Rec; p?: Program }[] => { const ps = x.r.programs ?? []; const uniq = ps.filter((p, i) => ps.findIndex(q => q.code === p.code) === i); return uniq.length ? uniq.map(p => ({ x, p })) : [{ x, p: undefined }]; });
  const group = new Set(recs.map(x => x.e.key));
  const out = recs.flatMap(x => (x.r.relations ?? []).filter(l => !l.targetKey || !group.has(l.targetKey)).map(l => ({ x, l, dir: 0 as const, other: l.targetKey ? ix.byKey.get(l.targetKey) : undefined, otherId: l.targetId })));
  const inc = recs.flatMap(x => (x.r.linkedFrom ?? []).filter(l => !group.has(l.key)).map(l => ({ x, l, dir: 1 as const, other: ix.byKey.get(l.key), otherId: l.key })));
  // One row per linked entity and relation type, citing every record that states it
  const relMap = new Map<string, { dir: 0 | 1; type: string; role?: string; share?: number; start?: string; end?: string; other?: Entry; otherId: string; refs: number[] }>();
  const links: { x: Rec; l: { type: string; role?: string; sharePercentage?: number; startDate?: string; endDate?: string }; dir: 0 | 1; other?: Entry; otherId: string }[] = [...out, ...inc];
  for (const { x, l, dir, other, otherId } of links) {
    const k = `${dir}|${l.type}|${other?.group ?? otherId}`, s = relMap.get(k);
    if (s) { if (!s.refs.includes(x.n)) s.refs.push(x.n); s.role ??= l.role; }
    else relMap.set(k, { dir, type: l.type, role: l.role, share: l.sharePercentage, start: l.startDate, end: l.endDate, other, otherId, refs: [x.n] });
  }
  const rels = [...relMap.values()];
  // The photo of the record that was opened comes first, then the other lists' photos
  const images = collect<RawImage>([here, ...recs.filter(x => x !== here)], r => r.images, i => i.url);
  const mentions = collect<NewsArticle>(recs, r => news.records[`${r.listSource}/${r.id}`], a => a.url).sort((a, b) => b.v.seenAt.localeCompare(a.v.seenAt));
  const pages = collect<RawLink>(recs, r => r.links, l => l.url).sort((a, b) => (a.v.date ?? '').localeCompare(b.v.date ?? ''));
  const pageGroups = LINK_KINDS.map(([k, label]) => [label, pages.filter(p => p.v.kind === k)] as const).filter(g => g[1].length);
  const firstSeen = minOf(recs.map(x => x.r.firstSeen)), lastSeen = maxOf(recs.map(x => x.r.lastSeen)), lastChange = maxOf(recs.map(x => x.r.lastChange));
  const firstListed = minOf(recs.map(x => day(x.r.listedDate)));
  const programCount = new Set(listings.filter(l => l.p).map(l => `${l.x.e.source}:${l.p!.code}`)).size;

  // A sentence a reader can take in at a glance, built only from what the lists state
  const srcNames = recs.map(x => x.src?.name ?? x.e.source);
  const born = isPerson && (dobs.length || pobs.length) ? ` born${dobs.length ? ` ${dobs[0].v}` : ''}${pobs.length ? ` in ${pobs[0].v}` : ''}` : '';
  const natTxt = nats.length ? `${isPerson ? ', national of' : ', of'} ${list(nats.slice(0, 3).map(n => n.v))}` : '';
  const summary = `${TYPE_LABEL[type]}${born}${natTxt}. Listed by ${recs.length === 1 ? srcNames[0] : `${recs.length} sources: ${list(srcNames)}`}${programCount ? `, under ${programCount} program${programCount === 1 ? '' : 's'}` : ''}${firstListed ? `, since ${firstListed}` : ''}.`;

  const copy = async () => { const u = location.href; try { await navigator.clipboard.writeText(u); toast.show('Permalink copied'); } catch { prompt('Copy permalink', u); } };
  const download = () => { const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([JSON.stringify(recs.map(x => x.r), null, 2)], { type: 'application/json' })), download: `${entry.source}-${entry.id}.json` }); a.click(); URL.revokeObjectURL(a.href); toast.show('Downloading JSON'); };
  const Row = ({ k, children }: { k: string; children: ReactNode }) => <tr><th>{k}</th><td>{children}</td></tr>;
  const Vals = ({ xs, mono, render }: { xs: Sourced<string>[]; mono?: boolean; render?: (v: string) => ReactNode }) => xs.length === 1 && recs.length < 2 ? <span className={mono ? 'num' : ''}>{render ? render(xs[0].v) : xs[0].v}</span> : <ul>{xs.map(x => <li key={x.v}><span className={mono ? 'num' : ''}>{render ? render(x.v) : x.v}</span><Refs refs={x.refs} recs={recs} /></li>)}</ul>;
  const nameRow = (a: Sourced<RawName>) => <li key={a.v.fullName + a.v.nameType}><span className={a.v.strength === 'WEAK' ? 'muted' : ''}>{a.v.fullName}</span> <span className="xs muted num">{NAME_TYPE[a.v.nameType] ?? a.v.nameType.toLowerCase()}{a.v.strength === 'WEAK' && ' · weak'}</span><Refs refs={a.refs} recs={recs} /></li>;
  const entityLink = (o: Entry | undefined, fallback: string) => o
    ? <Link to={entityPath(o)} className="cl" style={{ display: 'inline-flex' }}><Icon name={TYPE_ICON[o.type]} size={14} style={{ color: 'var(--muted)' }} />{o.name}</Link>
    : <span className="muted num" title="Not among the published records">{fallback}</span>;

  return (
    <>
      <nav className="small muted" style={{ marginBottom: 14 }} aria-label="Breadcrumb"><Link to="/search">Search</Link> <span style={{ margin: '0 6px' }}>/</span> <span className="num">{entry.key}</span></nav>
      <div className="ehd">
        {images.length > 0 && <Photo img={images[0].v} name={name} logo={!isPerson && type !== 'vessel' && type !== 'aircraft'} />}
        <div style={{ flex: '1 1 480px', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}><TypeBadge type={type} /><TopicBadges topics={topics} /></div>
          <h1>{name}</h1>
          <p className="summary">{summary}</p>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 12 }}>{recs.map(x => <button key={x.e.key} onClick={() => goTo(x.n)}><Badge variant={x === here ? 'acc' : undefined} cc={x.src?.cc}>{x.src?.name ?? x.e.source}</Badge></button>)}</div>
        </div>
        <div className="acts"><button className="btn" onClick={copy}><Icon name="link" />Copy permalink</button><button className="btn" onClick={download}><Icon name="download" />Download JSON</button></div>
      </div>

      <div className="eprof">
        <div style={{ display: 'grid', gap: 20, minWidth: 0 }}>
          <div className="card">
            <div className="card-h"><h3>Profile</h3><span className="xs muted">{recs.length > 1 ? `merged from ${recs.length} lists; numbers cite the sources stating a value when not all of them do` : `from ${srcNames[0]}`}</span></div>
            <table className="facts"><tbody>
              <Row k="Type">{TYPE_LABEL[type]}</Row>
              {topics.length > 0 && <Row k="Topics"><span style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>{topics.map(t => <TopicBadge key={t} topic={t} />)}</span></Row>}
              <Row k="Name"><b style={{ color: 'var(--heading)' }}>{name}</b></Row>
              <Row k="Other names">{byScript.length ? byScript.map(([s, as]) => <Fragment key={s}><div className="scr">{SCRIPT_LABEL[s]}</div><ul>{as.map(nameRow)}</ul></Fragment>) : <span className="muted">None recorded</span>}</Row>
              {isPerson && dobs.length > 0 && <Row k="Date of birth"><Vals xs={dobs} mono /></Row>}
              {isPerson && pobs.length > 0 && <Row k="Place of birth"><Vals xs={pobs} /></Row>}
              {isPerson && genders.length > 0 && <Row k="Gender"><Vals xs={genders} render={g => GENDER[g] ?? g} /></Row>}
              {isPerson && deceased.length > 0 && <Row k="Deceased"><Vals xs={deceased} render={() => 'Reported dead'} /></Row>}
              {vesselRows.filter(v => v[1].length).map(([k, xs, mono]) => <Row key={k} k={k}><Vals xs={xs} mono={mono} render={v => (/^\d+$/.test(v) ? tons(+v) : v)} /></Row>)}
              {nats.length > 0 && <Row k={isPerson ? 'Nationality' : 'Jurisdiction'}><Vals xs={nats} /></Row>}
              {cits.length > 0 && <Row k="Citizenship"><Vals xs={cits} /></Row>}
              {countries.length > 0 && <Row k="Countries"><span style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>{countries.map(c => <Badge key={c} cc={c}>{D.countryByCc[c]?.name ?? countryName(c)}</Badge>)}</span></Row>}
              {addrs.length > 0 && <Row k="Addresses"><Vals xs={addrs} /></Row>}
              {ids.length > 0 && <Row k="Identifiers"><table className="idt"><tbody>{ids.map(({ v: i, refs }) => <tr key={i.type + i.value}><td className="muted">{ID_TYPE[i.type] ?? i.type}</td><td className="num">{i.value}</td><td className="small muted">{i.issuingCountry}{i.remarks && ` · ${i.remarks}`}</td><td><Refs refs={refs} recs={recs} /></td></tr>)}</tbody></table></Row>}
              {(firstSeen || lastChange) && <Row k="Record history"><span className="small">{firstSeen && <>First seen <span className="num">{firstSeen}</span></>}{lastChange && <> · last changed <span className="num">{lastChange}</span></>}{lastSeen && <> · last seen <span className="num">{lastSeen}</span></>}</span></Row>}
            </tbody></table>
          </div>

          <div className="card">
            <div className="card-h"><h3>Sanctions and listings</h3><span className="xs muted">{listings.length} listing{listings.length === 1 ? '' : 's'} on {recs.length} list{recs.length === 1 ? '' : 's'}</span></div>
            <div style={{ overflowX: 'auto' }}>
              <table className="tbl lst">
                <thead><tr><th>Source</th><th>Program</th><th>Topics</th><th>Listed</th><th>Updated</th></tr></thead>
                <tbody>{listings.map(({ x, p }, i) => (
                  <tr key={i}>
                    <td><button className="cl" onClick={() => goTo(x.n)} style={{ textAlign: 'left' }}><Flag cc={x.src?.cc} /><span>{x.src?.name ?? x.e.source}</span>{recs.length > 1 && <span className="refs"><span>{x.n}</span></span>}</button><div className="xs muted">{x.src?.authority}</div></td>
                    <td>{p ? <><Chip accent>{p.code}</Chip>{p.name && p.name !== p.code && <div className="xs muted" style={{ marginTop: 4 }}>{p.name}</div>}</> : <span className="muted small">No program stated</span>}</td>
                    <td><span style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}><TopicBadges topics={x.r.topics ?? x.e.topics} /></span></td>
                    <td className="num">{day(x.r.listedDate) ?? <span className="muted">—</span>}</td>
                    <td className="num">{day(x.r.lastUpdated) ?? <span className="muted">—</span>}</td>
                  </tr>))}
                </tbody>
              </table>
            </div>
          </div>

          {pageGroups.length > 0 && (
            <div className="card">
              <div className="card-h"><h3>Sources and articles</h3><span className="xs muted">{pages.length} page{pages.length === 1 ? '' : 's'} about this {TYPE_LABEL[type].toLowerCase()}</span></div>
              <div className="card-b links">{pageGroups.map(([label, xs]) => (
                <div key={label}>
                  <div className="flab">{label}</div>
                  <ul>{xs.map(({ v: l, refs }) => (
                    <li key={l.url}>
                      <a href={l.url} target="_blank" rel="noopener noreferrer" className="cl">{l.title ?? host(l.url)} <Icon name="ext" size={12} /></a>
                      <span className="xs muted">{host(l.url)}{l.date && <> · <span className="num">{l.date}</span></>}</span>
                      <Refs refs={refs} recs={recs} />
                    </li>))}
                  </ul>
                </div>))}
              </div>
            </div>
          )}

          {mentions.length > 0 && (
            <div className="card">
              <div className="card-h"><h3>Recent news mentions</h3><span className="xs muted">{mentions.length} article{mentions.length === 1 ? '' : 's'} in the last {news.days ?? 30} days</span></div>
              <div className="card-b links">
                <p className="xs muted news-note"><Badge>Unverified</Badge> Found by name only, in news that uses words such as fraud or sanctions. An article may be about someone else with the same name.</p>
                <ul>{mentions.map(({ v: a, refs }) => (
                  <li key={a.url}>
                    <a href={a.url} target="_blank" rel="noopener noreferrer" className="cl">{a.title || host(a.url)} <Icon name="ext" size={12} /></a>
                    <span className="xs muted">{a.domain ?? host(a.url)} · <span className="num">{day(a.seenAt)}</span>{a.mentionedAs && <> · as “{a.mentionedAs}”</>}{a.terms?.length ? <> · {a.terms.join(', ')}</> : null}</span>
                    <Refs refs={refs} recs={recs} />
                  </li>))}
                </ul>
              </div>
            </div>
          )}

          {rels.length > 0 && (
            <div className="card">
              <div className="card-h"><h3>Relations</h3><span className="xs muted">{rels.length} link{rels.length === 1 ? '' : 's'} stated by the lists</span></div>
              <div style={{ overflowX: 'auto' }}>
                <table className="tbl lst">
                  <thead><tr><th>Relation</th><th>Entity</th><th>Details</th>{recs.length > 1 && <th>Source</th>}</tr></thead>
                  <tbody>{rels.map((r, i) => (
                    <tr key={i}>
                      <td><b style={{ fontWeight: 500, color: 'var(--heading)' }}>{(REL[r.type] ?? [r.type, r.type])[r.dir]}</b>{r.role && <div className="xs muted">{r.role}</div>}</td>
                      <td>{entityLink(r.other, r.otherId)}{r.other && <div className="xs muted">{D.byId[r.other.source]?.name ?? r.other.source}</div>}</td>
                      <td className="small">{[r.share != null && `${r.share}%`, r.start && `from ${r.start}`, r.end && `until ${r.end}`].filter(Boolean).join(' · ') || <span className="muted">—</span>}</td>
                      {recs.length > 1 && <td><Refs refs={r.refs} recs={recs} /></td>}
                    </tr>))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <AssociationGraph entry={entry} ix={ix} />

          <section>
            <div className="blk-h" style={{ marginBottom: 12 }}><h2>Data sources</h2><p>What each list publishes on this {TYPE_LABEL[type].toLowerCase()}, as tonight's snapshot read it.</p></div>
            <div style={{ display: 'grid', gap: 16 }}>{recs.map(x => <SourceRecord key={x.e.key} x={x} many={recs.length > 1} current={x === here} />)}</div>
          </section>
        </div>

        <aside className="eside">
          <div className="card">
            <div className="card-h"><h3>Sources</h3><span className="xs muted">{recs.length}</span></div>
            <div className="card-b" style={{ display: 'grid', gap: 10, paddingTop: 12, paddingBottom: 12 }}>
              {recs.map(x => <button key={x.e.key} className="srcl" onClick={() => goTo(x.n)}>{recs.length > 1 && <span className="refs"><span>{x.n}</span></span>}<Flag cc={x.src?.cc} /><span style={{ flex: 1, minWidth: 0 }}>{x.src?.name ?? x.e.source}<span className="xs muted num" style={{ display: 'block' }}>{x.e.id}</span></span></button>)}
            </div>
          </div>
          <div className="card"><div className="card-b" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Stat l="First listed" v={firstListed} />
            <Stat l="Last updated" v={maxOf(recs.map(x => day(x.r.lastUpdated)))} />
            <Stat l="First seen" v={firstSeen} />
            <Stat l="Last changed" v={lastChange} />
          </div></div>
          <div className="callout"><Icon name="info" size={18} /><span>{recs.length > 1 ? 'Records from several lists are matched by name, identifiers and date of birth. ' : ''}Sieve normalises list data; each issuing authority's publication is authoritative.</span></div>
        </aside>
      </div>
      <p className="xs muted" style={{ marginTop: 24 }}>Snapshot {S.date} {S.time}{S.commit && ` · commit ${S.commit}`} · record <span className="num">{entry.key}</span></p>
      {toast.node}
    </>
  );
}

/**
 * The entity's photo or logo, loaded from its publisher only when the page is open and without telling the publisher which
 * page asked, with the credit and licence the publisher requires.
 */
function Photo({ img, name, logo }: { img: RawImage; name: string; logo: boolean }) {
  const [failed, setFailed] = useState(false);
  if (failed) return null;
  const credit = [img.credit, img.licence].filter(Boolean).join(' · ');
  return (
    <figure className={'ephoto' + (logo ? ' logo' : '')}>
      <a href={img.pageUrl ?? img.url} target="_blank" rel="noopener noreferrer" title="Open the publisher's page">
        <img src={img.thumbnailUrl ?? img.url} alt={`Picture of ${name}`} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} />
      </a>
      {credit && <figcaption className="xs muted">{credit}</figcaption>}
    </figure>
  );
}

const Stat = ({ l, v }: { l: string; v?: string }) => <div><div className="xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.04em' }}>{l}</div><div className="num" style={{ fontSize: 15, color: v ? 'var(--heading)' : 'var(--muted)' }}>{v ?? '—'}</div></div>;

/** One list's record of the entity: the list's description, then what it says, with its listing details and dates. */
function SourceRecord({ x, many, current }: { x: Rec; many: boolean; current: boolean }) {
  const { r, src, e } = x;
  const progs = (r.programs ?? []).filter((p, i, ps) => ps.findIndex(q => q.code === p.code) === i);
  const aliases = (r.aliases ?? []).filter(a => a.fullName !== r.primaryName.fullName);
  return (
    <div className={'card srcrec' + (current ? ' cur' : '')} id={`src-${x.n}`}>
      <div className="card-h">
        <span className="cl" style={{ minWidth: 0 }}>{many && <span className="refs"><span>{x.n}</span></span>}<Flag cc={src?.cc} /><b style={{ color: 'var(--heading)' }}>{src?.name ?? e.source}</b>{src && <Chip>{src.format}</Chip>}{current && <span className="xs muted">· the record you opened</span>}</span>
        <span className="cl xs">{src && <Link to={`/source/${src.id}`}>About this source</Link>}</span>
      </div>
      <div className="card-b" style={{ display: 'grid', gap: 14 }}>
        {src?.description && <p className="small" style={{ color: 'var(--text-2)' }}>{src.description}</p>}
        <dl className="kv">
          <div><dt>Name on this list</dt><dd>{r.primaryName.fullName}</dd></div>
          <div><dt>Record id</dt><dd className="num">{r.id}</dd></div>
          <div><dt>Topics</dt><dd style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}><TopicBadges topics={r.topics ?? e.topics} /></dd></div>
          <div><dt>Aliases</dt><dd>{aliases.length || <span className="muted">none</span>}</dd></div>
          <div><dt>Listed</dt><dd className="num">{day(r.listedDate) ?? '—'}</dd></div>
          <div><dt>Updated by the list</dt><dd className="num">{day(r.lastUpdated) ?? '—'}</dd></div>
          <div><dt>First seen by Sieve</dt><dd className="num">{r.firstSeen ?? '—'}</dd></div>
          <div><dt>Last changed</dt><dd className="num">{r.lastChange ?? '—'}</dd></div>
        </dl>
        {progs.length > 0 && <div><div className="flab">Programs</div><div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>{progs.map(p => <span key={p.code} title={p.name}><Chip accent>{p.code}</Chip></span>)}</div>{progs.some(p => p.name && p.name !== p.code) && <ul className="small muted" style={{ margin: '6px 0 0', paddingLeft: 16 }}>{progs.filter(p => p.name && p.name !== p.code).map(p => <li key={p.code}>{p.name}</li>)}</ul>}</div>}
        {(r.listingReasons?.length ?? 0) > 0 && <div><div className="flab">Reasons for listing</div>{r.listingReasons!.map((t, i) => <p key={i} className="small" style={{ whiteSpace: 'pre-line', marginTop: i ? 6 : 0 }}>{t}</p>)}</div>}
        {r.remarks && <div><div className="flab">Remarks</div><p className="small" style={{ whiteSpace: 'pre-line' }}>{r.remarks}</p></div>}
        {src && <div className="xs muted cl" style={{ flexWrap: 'wrap', gap: 12 }}><span>Published by {src.authority}</span><a href={src.homepage} target="_blank" rel="noopener" className="cl">{host(src.homepage)} <Icon name="ext" size={12} /></a>{src.listUri && <a href={src.listUri} target="_blank" rel="noopener" className="cl">Data file <Icon name="ext" size={12} /></a>}</div>}
      </div>
    </div>
  );
}
