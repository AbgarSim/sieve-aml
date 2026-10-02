import { Link } from 'react-router-dom';
import { SIEVE } from '../data/snapshot';

export function Footer() {
  const S = SIEVE.snapshot;
  return (
    <footer className="ftr"><div className="wrap ftr-in">
      <span className="num">Snapshot {S.date}</span>{S.commit && <a className="num" href={`https://github.com/AbgarSim/sieve-aml/commit/${S.commit}`} target="_blank" rel="noopener">commit {S.commit}</a>}<span>MIT licence</span>
      <a href="https://github.com/AbgarSim/sieve-aml" target="_blank" rel="noopener">GitHub</a><Link to="/components">Component sheet</Link>
      <span className="dis">Sieve is a screening tool, not legal advice. Official lists are authoritative.</span>
    </div></footer>
  );
}
