import { useEffect, useRef, type ReactNode } from 'react';
import { Icon } from '../lib/icons';

export function SlideOver({ open, onClose, title, aside, footer, children }: { open: boolean; onClose: () => void; title: ReactNode; aside?: ReactNode; footer?: ReactNode; children: ReactNode }) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    ref.current?.focus();
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', k);
    return () => document.removeEventListener('keydown', k);
  }, [open, onClose]);
  return (
    <>
      <div className={'ov' + (open ? ' on' : '')} onClick={onClose} />
      <aside className={'so' + (open ? ' on' : '')} role="dialog" aria-modal="true" aria-hidden={!open}>
        <div className="so-h"><h2>{title}</h2>{aside}<button ref={ref} className="ibtn" style={{ marginLeft: 'auto' }} aria-label="Close" onClick={onClose}><Icon name="x" size={18} /></button></div>
        <div className="so-b">{children}</div>
        {footer && <div className="so-f">{footer}</div>}
      </aside>
    </>
  );
}
