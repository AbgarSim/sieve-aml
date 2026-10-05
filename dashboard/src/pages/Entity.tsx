import { Fragment, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Flag } from '../components/Flag';
import { Badge, Chip, TypeBadge } from '../components/Badges';
import { Icon, TYPE_ICON } from '../lib/icons';
import { RAW_TYPE, SIEVE, TYPE_LABEL } from '../data/snapshot';
import { loadEntity, sameEntity, type Entry } from '../data/search';
import type { RawEntity, RawName } from '../data/raw';
import { useIndex } from '../data/useIndex';
import { countryName } from '../data/iso';
import { host } from '../lib/format';
import { useToast } from '../lib/useToast';
import { entityPath } from './Search';

const SCRIPTS = ['LATIN', 'CYRILLIC', 'ARABIC', 'CJK', 'OTHER'];
const SCRIPT_LABEL: Record<string, string> = { LATIN: 'Latin', CYRILLIC: 'Cyrillic', ARABIC: 'Arabic', CJK: 'CJK', OTHER: 'Other script' };
const NAME_TYPE: Record<string, string> = { AKA: 'a.k.a.', FKA: 'f.k.a.', MAIDEN: 'maiden name', PRIMARY: 'primary' };
const GENDER: Record<string, string> = { MALE: 'Male', FEMALE: 'Female', OTHER: 'Other' };
const tons = (n: number) => n.toLocaleString('en-US');
const ID_TYPE: Record<string, string> = { PASSPORT: 'Passport', NATIONAL_ID: 'National ID', TAX_ID: 'Tax ID', IMO_NUMBER: 'IMO number', MMSI: 'MMSI', REGISTRATION_NUMBER: 'Registration number', SWIFT_BIC: 'SWIFT/BIC', LEI: 'LEI', BUSINESS_REGISTRATION: 'Business registration', OTHER: 'Other' };
const Lbl = ({ children }: { children: React.ReactNode }) => <div className="xs muted" style={{ textTransform: 'uppercase', letterSpacing: '.04em' }}>{children}</div>;
const day = (iso?: string) => (iso ? iso.slice(0, 10) : '—');
const address = (a: NonNullable<RawEntity['addresses']>[number]) => a.fullAddress || [a.street, a.city, a.stateOrProvince, a.postalCode, a.country].filter(Boolean).join(', ');
const uniq = <T,>(xs: T[]) => [...new Set(xs)];

function Shell({ children }: { children: React.ReactNode }) {
  return <><Header active="Search" /><main className="wrap page">{children}</main><Footer /></>;
}

export default function Entity() {
  const { source = '', id = '' } = useParams();
  const key = `${source}/${id}`;
  const { ix, error } = useIndex();
  const [rec, setRec] = useState<RawEntity | null | undefined>(undefined);
  const entry: Entry | undefined = ix?.byKey.get(key);
  useEffect(() => { if (!ix) return; if (!entry) { setRec(null); return; } let live = true; setRec(undefined); loadEntity(entry).then(r => live && setRec(r ?? null), () => live && setRec(null)); return () => { live = false; }; }, [ix, entry]);
  const toast = useToast();

  if (error) return <Shell><div className="card empty"><Icon name="warn" size={36} /><h3>Snapshot unavailable</h3><p>{error}</p></div></Shell>;
  if (!ix || rec === undefined) return <Shell><div className="card" style={{ padding: 24, display: 'grid', gap: 12 }}><div className="skl" style={{ height: 28, width: '40%' }} /><div className="skl" style={{ height: 14, width: '25%' }} /><div className="skl" style={{ height: 180 }} /></div></Shell>;
  if (!entry || !rec) return <Shell><div className="card empty"><Icon name="warn" size={36} /><h3>No record {key}</h3><p>It is not in the latest snapshot; it may have been delisted. <Link to="/search">Back to search</Link></p></div></Shell>;

  const D = SIEVE, S = D.snapshot, src = D.byId[rec.listSource], type = RAW_TYPE[rec.entityType];
  document.title = `${rec.primaryName.fullName} — Sieve`;
  const aliases = (rec.aliases ?? []).filter(a => a.fullName !== rec.primaryName.fullName);
  const byScript = SCRIPTS.map(s => [s, aliases.filter(a => (a.script ?? 'LATIN') === s)] as const).filter(x => x[1].length);
  const ids = rec.identifiers ?? [], addrs = uniq((rec.addresses ?? []).map(address).filter(Boolean));
  const nats = uniq([...(rec.nationalities ?? []), ...(rec.citizenships ?? [])]);
  const progs = uniq((rec.programs ?? []).map(p => p.code)).map(code => (rec.programs ?? []).find(p => p.code === code)!);
  const others = sameEntity(ix, entry);
  const isPerson = type === 'individual';
  const copy = async () => { const u = location.href; try { await navigator.clipboard.writeText(u); toast.show('Permalink copied'); } catch { prompt('Copy permalink', u); } };
  const download = () => { const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([JSON.stringify(rec, null, 2)], { type: 'application/json' })), download: `${rec.listSource}-${rec.id}.json` }); a.click(); URL.revokeObjectURL(a.href); toast.show('Downloading JSON'); };
  const Row = ({ k, children }: { k: string; children: React.ReactNode }) => <tr><th>{k}</th><td>{children}</td></tr>;
  const nameRow = (a: RawName) => <li key={a.fullName + a.nameType} style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}><span className={a.strength === 'WEAK' ? 'muted' : ''}>{a.fullName}</span><span className="xs muted num">{NAME_TYPE[a.nameType] ?? a.nameType.toLowerCase()}{a.strength === 'WEAK' && ' · weak'}</span></li>;
  return (
    <Shell>
      <nav className="small muted" style={{ marginBottom: 14 }} aria-label="Breadcrumb"><Link to="/search">Search</Link> <span style={{ margin: '0 6px' }}>/</span> <span className="num">{key}</span></nav>
      <div className="ehd">
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 8 }}><TypeBadge type={type} /><Badge variant="red"><Icon name="shield" size={12} />Sanctioned</Badge><span className="num xs muted">{rec.id}</span></div>
          <h1>{rec.primaryName.fullName}</h1>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}><Badge variant="acc" cc={src?.cc}>{src?.name ?? rec.listSource}</Badge>{others.map(o => <Link key={o.key} to={entityPath(o)}><Badge cc={D.byId[o.source]?.cc}>{D.byId[o.source]?.name ?? o.source}</Badge></Link>)}</div>
        </div>
        <div className="acts"><button className="btn" onClick={copy}><Icon name="link" />Copy permalink</button><button className="btn" onClick={download}><Icon name="download" />Download JSON</button></div>
      </div>
      <div className="egrid">
        <div className="card">
          <div className="card-h"><h3>Identity</h3><span className="xs muted">{aliases.length} alias{aliases.length === 1 ? '' : 'es'} · {ids.length} identifier{ids.length === 1 ? '' : 's'}</span></div>
          <table className="facts"><tbody>
            <Row k="Primary name"><b style={{ color: 'var(--heading)' }}>{rec.primaryName.fullName}</b></Row>
            <Row k="Aliases">{byScript.length ? byScript.map(([s, as]) => <Fragment key={s}><div className="scr">{SCRIPT_LABEL[s]}</div><ul>{as.map(nameRow)}</ul></Fragment>) : <span className="muted">None recorded</span>}</Row>
            {isPerson && (rec.datesOfBirth?.length ?? 0) > 0 && <Row k="Date of birth"><ul>{rec.datesOfBirth!.map(d => <li key={d} className="num">{d}</li>)}</ul></Row>}
            {isPerson && (rec.placesOfBirth?.length ?? 0) > 0 && <Row k="Place of birth">{rec.placesOfBirth!.join(' · ')}</Row>}
            {isPerson && rec.gender && <Row k="Gender">{GENDER[rec.gender] ?? rec.gender}</Row>}
            {isPerson && rec.deceased && <Row k="Deceased">Reported dead by the list</Row>}
            {rec.vessel?.flag && <Row k="Flag">{rec.vessel.flag}</Row>}
            {rec.vessel?.type && <Row k="Vessel type">{rec.vessel.type}</Row>}
            {rec.vessel?.callSign && <Row k="Call sign"><span className="num">{rec.vessel.callSign}</span></Row>}
            {rec.vessel?.tonnage != null && <Row k="Tonnage"><span className="num">{tons(rec.vessel.tonnage)}</span></Row>}
            {rec.vessel?.grossRegisteredTonnage != null && <Row k="Gross registered tonnage"><span className="num">{tons(rec.vessel.grossRegisteredTonnage)}</span></Row>}
            {nats.length > 0 && <Row k="Nationality"><ul>{nats.map(n => <li key={n}>{n}</li>)}</ul></Row>}
            {addrs.length > 0 && <Row k="Addresses"><ul>{addrs.map(a => <li key={a}>{a}</li>)}</ul></Row>}
            {ids.length > 0 && <Row k="Identifiers"><table style={{ borderCollapse: 'collapse', fontSize: 13 }}><tbody>{ids.map((i, n) => <tr key={n}><td style={{ padding: '2px 14px 2px 0', color: 'var(--muted)' }}>{ID_TYPE[i.type] ?? i.type}</td><td className="num" style={{ padding: '2px 10px 2px 0' }}>{i.value}</td><td className="small muted">{i.issuingCountry}</td></tr>)}</tbody></table></Row>}
            {(rec.listingReasons?.length ?? 0) > 0 && <Row k="Listing reasons">{rec.listingReasons!.length === 1 ? rec.listingReasons![0] : <ul>{rec.listingReasons!.map(r => <li key={r}>{r}</li>)}</ul>}</Row>}
            {rec.remarks && <Row k="Remarks">{rec.remarks}</Row>}
            {entry.countries.length > 0 && <Row k="Linked countries"><span style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>{entry.countries.map(c => <Badge key={c} cc={c}>{D.countryByCc[c]?.name ?? countryName(c)}</Badge>)}</span></Row>}
          </tbody></table>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr)', gap: 16, alignContent: 'start', minWidth: 0 }}>
          <div className="card">
            <div className="card-h"><h3>Sanctions programs</h3><span className="xs muted">{progs.length} program{progs.length === 1 ? '' : 's'}</span></div>
            <div className="card-b prog">
              <div className="src">
                <div className="t"><span className="cl"><Flag cc={src?.cc} /><b style={{ color: 'var(--heading)' }}>{src?.name ?? rec.listSource}</b></span>{src && <Chip>{src.format}</Chip>}</div>
                <div className="codes">{progs.map(p => <span key={p.code} title={p.name}><Chip accent>{p.code}</Chip></span>)}{!progs.length && <span className="small muted">No program recorded</span>}</div>
                {progs.some(p => p.name && p.name !== p.code) && <ul className="small muted" style={{ margin: '6px 0 0', paddingLeft: 16 }}>{progs.filter(p => p.name && p.name !== p.code).map(p => <li key={p.code}>{p.name}</li>)}</ul>}
                <div className="xs muted">{src?.authority}</div>
              </div>
            </div>
          </div>
          <div className="card"><div className="card-b" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div><Lbl>Listed</Lbl><div className="num" style={{ fontSize: 16, color: 'var(--heading)' }}>{day(rec.listedDate)}</div></div>
            <div><Lbl>Last updated</Lbl><div className="num" style={{ fontSize: 16, color: 'var(--heading)' }}>{day(rec.lastUpdated)}</div></div>
          </div></div>
          <div className="card">
            <div className="card-h"><h3>Same entity on other lists</h3><span className="xs muted">matched by name, identifiers and date of birth</span></div>
            <div className="card-b" style={{ paddingTop: 8, paddingBottom: 8 }}>
              {others.map(o => <div key={o.key} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0', borderBottom: '1px solid var(--border)', fontSize: 13 }}><Badge variant="acc" cc={D.byId[o.source]?.cc}>{D.byId[o.source]?.name ?? o.source}</Badge><span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{o.name} <span className="num muted xs">{o.id}</span></span><Link className="xs" to={entityPath(o)}>Open</Link></div>)}
              {!others.length && <div className="small muted" style={{ padding: '6px 0' }}><Icon name={TYPE_ICON[type]} size={14} style={{ verticalAlign: -2, marginRight: 6 }} />No other list was found to carry this {TYPE_LABEL[type].toLowerCase()}.</div>}
            </div>
          </div>
          {src && <div className="callout"><Icon name="info" size={18} /><span>Published by {src.authority}: <a href={src.homepage} target="_blank" rel="noopener">{host(src.homepage)}</a>. Sieve normalises list data; the issuing authority's publication is authoritative.</span></div>}
        </div>
      </div>
      <p className="xs muted" style={{ marginTop: 24 }}>Snapshot {S.date} {S.time}{S.commit && ` · commit ${S.commit}`} · record <span className="num">{key}</span></p>
      {toast.node}
    </Shell>
  );
}
