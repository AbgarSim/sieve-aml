import type { ReactNode } from 'react';
import { fmt } from '../lib/format';
import { Flag } from './Flag';
import { Icon, TYPE_ICON } from '../lib/icons';
import { TYPE_LABEL, type EntityType, type Status } from '../data/snapshot';

export const Chip = ({ children, accent }: { children: ReactNode; accent?: boolean }) => (
  <span className="chip" style={accent ? { borderColor: 'transparent', background: 'var(--accent-soft)', color: 'var(--accent-text)' } : undefined}>{children}</span>
);

export const STATUS_LABEL: Record<Status, string> = { loaded: 'Loaded', empty: 'Empty', failed: 'Failed', 'needs-key': 'Needs key', skipped: 'Skipped' };
export const StatusDot = ({ status }: { status: Status }) => <span className={'dot ' + status}>{STATUS_LABEL[status]}</span>;

export const Badge = ({ children, variant, cc }: { children: ReactNode; variant?: 'acc' | 'red' | 'type'; cc?: string }) => (
  <span className={'badge' + (variant ? ' ' + variant : '')}>{cc && <Flag cc={cc} />}{children}</span>
);
export const TypeBadge = ({ type }: { type: EntityType }) => <Badge variant="type"><Icon name={TYPE_ICON[type]} size={12} />{TYPE_LABEL[type]}</Badge>;

export const Delta = ({ n }: { n: number }) => (
  <span className={'dl num ' + (n > 0 ? 'pos' : n < 0 ? 'neg' : 'muted')}>{n > 0 ? '+' : n < 0 ? '−' : '±'}{fmt(Math.abs(n))}</span>
);
