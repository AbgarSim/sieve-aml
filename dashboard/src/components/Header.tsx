import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { Icon } from '../lib/icons';
import { useTheme } from '../lib/theme';
import { SIEVE } from '../data/snapshot';

const LINKS: [string, string][] = [['Overview', 'overview'], ['Map', 'map'], ['Sources', 'sources'], ['Quality', 'quality'], ['Benchmarks', 'benchmarks'], ['Search', '/search']];

/** A link to a dashboard section. The app uses hash routing, so sections are a query parameter. */
export function SectionLink({ id, className, children, onClick, ...rest }: { id: string; className?: string; children: ReactNode; onClick?: () => void; 'aria-current'?: 'page' }) {
  return <Link to={`/?section=${id}`} className={className} onClick={() => { onClick?.(); document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' }); }} {...rest}>{children}</Link>;
}

export function Header({ active, hideSearch, subnav, activeSection }: { active?: string; hideSearch?: boolean; subnav?: [string, string][]; activeSection?: string }) {
  const { theme, toggle } = useTheme();
  const nav = useNavigate();
  const [menu, setMenu] = useState(false);
  const q = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === '/' && !/input|textarea|select/i.test((document.activeElement as HTMLElement)?.tagName)) { e.preventDefault(); (document.getElementById('big-q') ?? q.current)?.focus(); } };
    document.addEventListener('keydown', k); return () => document.removeEventListener('keydown', k);
  }, []);
  const submit = (e: FormEvent) => { e.preventDefault(); nav('/search?q=' + encodeURIComponent(q.current?.value ?? '')); };
  return (
    <>
      <header className={'hdr' + (menu ? ' menu' : '')}>
        <div className="wrap hdr-in">
          <Link className="logo" to="/"><img src="sieve-aml-icon.svg" alt="" />SIEVE</Link>
          <nav className="nav" aria-label="Primary" onClick={() => setMenu(false)}>
            {LINKS.map(([l, to]) => (to.startsWith('/') ? <NavLink key={l} to={to} aria-current={l === active ? 'page' : undefined}>{l}</NavLink> : <SectionLink key={l} id={to} aria-current={l === active ? 'page' : undefined}>{l}</SectionLink>))}
          </nav>
          <div className="hdr-right">
            {SIEVE.snapshot.sample
              ? <span className="pill warn" title="Fictional sample data for local development"><span className="d" />Sample data</span>
              : <span className="pill" title={SIEVE.snapshot.generatedAt}><span className="d" />Snapshot · {SIEVE.snapshot.date} {SIEVE.snapshot.time}</span>}
            {!hideSearch && (
              <form className="search" role="search" onSubmit={submit}>
                <Icon name="search" /><input ref={q} name="q" placeholder="Screen a name…" autoComplete="off" aria-label="Search entities" /><span className="kbd">/</span>
              </form>
            )}
            <button className="ibtn" aria-label="Toggle theme" onClick={toggle}><Icon name={theme === 'dark' ? 'sun' : 'moon'} size={18} /></button>
            <a className="ibtn" href="https://github.com/AbgarSim/sieve-aml" target="_blank" rel="noopener" aria-label="GitHub repository"><Icon name="code" size={18} /></a>
            <button className="ibtn menu-btn" aria-label="Menu" onClick={() => setMenu(m => !m)}><Icon name="menu" size={18} /></button>
          </div>
        </div>
      </header>
      {subnav && (
        <div className="subnav"><div className="wrap subnav-in">
          {subnav.map(([l, id]) => <SectionLink key={id} id={id} className={activeSection === id ? 'on' : ''}>{l}</SectionLink>)}
        </div></div>
      )}
    </>
  );
}
