import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { SIEVE } from '../data/snapshot';
import { fmt } from '../lib/format';

/** Portrait shown in the author card; the initials stand in until the file is there. */
const PHOTO = 'about/abgar-simonean.jpg';

export default function About() {
  const D = SIEVE;
  const [photo, setPhoto] = useState(true);
  return (
    <>
      <Header active="About" />
      <main className="wrap page about">
        <section className="about-hero">
          <h1>An open-source sanctions screening project</h1>
          <p>Sieve fetches {D.sources.length} official sanctions and watch lists every night, combines them into one searchable database of {fmt(D.totalEntities)} records and screens names against it with typo tolerance. The code, the data pipeline and this site are open source under the MIT licence.</p>
          <div className="about-cta">
            <Link className="btn pri" to="/overview">Open the overview</Link>
            <Link className="btn" to="/search">Screen a name</Link>
            <a className="btn" href="https://github.com/AbgarSim/sieve-aml" target="_blank" rel="noopener">Source code on GitHub</a>
          </div>
        </section>
        <section className="card about-me" aria-labelledby="about-me">
          <div className="about-photo">
            {photo ? <img src={PHOTO} alt="Abgar Simonean" onError={() => setPhoto(false)} /> : <span aria-hidden="true">AS</span>}
          </div>
          <div>
            <h2 id="about-me">Abgar Simonean</h2>
            <p className="muted small">Author of Sieve</p>
            <p>I have spent much of my career in finance, building financial applications for many years. I have worked on the development of central bank applications and settlement engines, and on sanctions screening and AML compliance projects.</p>
            <p>That work left me concerned about sanctions hygiene in the current landscape of the world. I built Sieve as an open-source sanctions project, so that anyone can run it, inspect it and improve it.</p>
          </div>
        </section>
      </main>
      <Footer />
    </>
  );
}
