import { Link } from 'react-router-dom';
import { Badge, Chip } from './Badges';
import { Flag } from './Flag';
import { Icon, TYPE_ICON } from '../lib/icons';
import { SIEVE, TYPE_LABEL } from '../data/snapshot';
import { TYPE_BY_CODE } from '../data/search';
import { recordPath, sourceOf, type ChangeRecord } from '../data/changes';
import { countryName } from '../data/iso';

/** One record of the change log: its type, name (linked while it is still published), list, first program and countries. */
export function ChangeRow({ r, link, compact }: { r: ChangeRecord; link: boolean; compact?: boolean }) {
  const type = r.t ? TYPE_BY_CODE[r.t] : undefined, src = SIEVE.byId[sourceOf(r.k)];
  const name = r.n ?? r.k.slice(r.k.indexOf('/') + 1);
  return (
    <li>
      <Icon name={type ? TYPE_ICON[type] : 'doc'} size={14} style={{ color: 'var(--muted)', flex: 'none' }} />
      <span className="nm" title={type ? TYPE_LABEL[type] : undefined}>{link ? <Link to={recordPath(r.k)}>{name}</Link> : name}{!r.n && <span className="xs muted"> · name not kept</span>}</span>
      <Badge cc={src?.cc}>{src?.name ?? sourceOf(r.k)}</Badge>
      {!compact && r.p?.[0] && <Chip accent>{r.p[0]}{r.p.length > 1 && ` +${r.p.length - 1}`}</Chip>}
      {!compact && r.c?.slice(0, 2).map(c => <span key={c} className="cl xs muted" title={countryName(c)}><Flag cc={c} /></span>)}
    </li>
  );
}

/** Records of one kind, with a way to see more of them. */
export function ChangeList({ rows, total, link, shown, onMore, compact }: { rows: ChangeRecord[]; total: number; link: boolean; shown: number; onMore?: () => void; compact?: boolean }) {
  if (!rows.length) return <div className="empty" style={{ padding: '24px 16px' }}><span className="small">None</span></div>;
  const page = rows.slice(0, shown);
  return (
    <>
      <ul className="chg-list">{page.map(r => <ChangeRow key={r.k} r={r} link={link} compact={compact} />)}</ul>
      {(page.length < rows.length || rows.length < total) && (
        <div className="map-foot">
          <span>{page.length < rows.length ? `Showing ${page.length} of ${rows.length}` : `${rows.length} of ${total} listed; the nightly file keeps the first 200 of each kind`}</span>
          {page.length < rows.length && onMore && <button className="btn sm" onClick={onMore}>Show more</button>}
        </div>
      )}
    </>
  );
}
