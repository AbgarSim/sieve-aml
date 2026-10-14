import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Header } from '../components/Header';
import { Footer } from '../components/Footer';
import { Icon } from '../lib/icons';
import { REPORT_FORM, REPORT_PROGRAMS, draftProblems, issueUrl, type ReportDraft } from '../data/reports';

const box: React.CSSProperties = { width: '100%', padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface)', color: 'var(--text)', fontSize: 14, font: 'inherit' };
const label: React.CSSProperties = { display: 'block', fontWeight: 600, fontSize: 13, color: 'var(--heading)', marginBottom: 4 };
const hint: React.CSSProperties = { display: 'block', fontWeight: 400, color: 'var(--muted)', fontSize: 12, marginTop: 2 };

/** A form for reporting a suspected sanctions breach. It is filed as a public GitHub issue for review. */
export default function Report() {
  const [params] = useSearchParams();
  const first = params.get('program');
  const [d, setD] = useState<ReportDraft>({ party: '', programs: first && REPORT_PROGRAMS.includes(first) ? [first] : [], when: '', where: '', what: '', sources: '' });
  const [agree, setAgree] = useState([false, false]);
  const [tried, setTried] = useState(false);
  const [sent, setSent] = useState(false);
  useEffect(() => { document.title = 'Report a suspected breach — Sieve'; }, []);

  const set = (k: keyof ReportDraft) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setD(v => ({ ...v, [k]: e.target.value }));
  const toggle = (p: string) => setD(v => ({ ...v, programs: v.programs.includes(p) ? v.programs.filter(x => x !== p) : [...v.programs, p] }));
  const problems = [...draftProblems(d), ...(agree.every(Boolean) ? [] : ['Tick both confirmations.'])];
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setTried(true);
    if (problems.length) return;
    window.open(issueUrl(d), '_blank', 'noopener');
    setSent(true);
  };

  return (
    <>
      <Header active="Monitoring" />
      <main className="wrap page" style={{ maxWidth: 760 }}>
        <section className="blk">
          <div className="blk-h"><h1>Report a suspected breach</h1></div>
          <p className="muted" style={{ marginBottom: 16 }}>
            Tell Sieve about a suspected sanctions breach that the public sources you have found support. The maintainer checks every report against its sources. Accepted reports appear on the <Link to="/monitoring">Monitoring page</Link> marked as reported by a user, never as an official finding; reports that are not accepted are never shown there.
          </p>
          <div className="callout" style={{ marginBottom: 20 }}>
            <Icon name="info" size={18} />
            <span>Reports are filed as public issues on GitHub, so you need a GitHub account, and what you write is public there. Rely only on public sources and leave out personal details of private individuals. To report a crime, contact the enforcing authority or the police.</span>
          </div>

          {sent ? (
            <div className="card" style={{ padding: 20 }}>
              <h3 style={{ marginBottom: 8 }}>One last step on GitHub</h3>
              <p style={{ marginBottom: 12 }}>A GitHub page opened in a new tab with your report filled in. Press <b>Submit new issue</b> there to send it. You will get a reply on the issue, and an accepted report shows on the Monitoring page after the next nightly update.</p>
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <a className="btn pri" href={issueUrl(d)} target="_blank" rel="noopener">Open the GitHub page again</a>
                <button className="btn" onClick={() => setSent(false)}>Edit the report</button>
              </div>
            </div>
          ) : (
            <form className="card" style={{ padding: 20, display: 'grid', gap: 18 }} onSubmit={submit} noValidate>
              <div>
                <label style={label} htmlFor="r-party">Who is suspected <span className="muted">*</span><span style={hint}>The company, vessel, person in a public role or network, as the sources name it.</span></label>
                <input id="r-party" style={box} maxLength={200} value={d.party} onChange={set('party')} />
              </div>
              <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
                <legend style={label}>Sanctions programs <span className="muted">*</span><span style={hint}>The sanctions the activity may breach. Pick every one that applies.</span></legend>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
                  {REPORT_PROGRAMS.map(p => (
                    <label key={p} className="btn sm" style={{ cursor: 'pointer', borderColor: d.programs.includes(p) ? 'var(--accent)' : undefined }}>
                      <input type="checkbox" checked={d.programs.includes(p)} onChange={() => toggle(p)} />{p}
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="grid g2" style={{ gap: 14 }}>
                <div>
                  <label style={label} htmlFor="r-when">When<span style={hint}>A date or period, such as 2026-09.</span></label>
                  <input id="r-when" style={box} maxLength={100} value={d.when} onChange={set('when')} />
                </div>
                <div>
                  <label style={label} htmlFor="r-where">Where<span style={hint}>Countries, ports or routes involved.</span></label>
                  <input id="r-where" style={box} maxLength={200} value={d.where} onChange={set('where')} />
                </div>
              </div>
              <div>
                <label style={label} htmlFor="r-what">What happened <span className="muted">*</span><span style={hint}>What the sources show, in a few sentences. Say what is established and what is suspected.</span></label>
                <textarea id="r-what" style={{ ...box, minHeight: 120, resize: 'vertical' }} maxLength={1500} value={d.what} onChange={set('what')} />
                <span style={hint}>{d.what.length} / 1500</span>
              </div>
              <div>
                <label style={label} htmlFor="r-sources">Public sources <span className="muted">*</span><span style={hint}>One link per line: news articles, court filings, ship-tracking pages, company records.</span></label>
                <textarea id="r-sources" style={{ ...box, minHeight: 80, resize: 'vertical' }} placeholder="https://" value={d.sources} onChange={set('sources')} />
              </div>
              <div style={{ display: 'grid', gap: 8 }}>
                {['Everything in this report comes from the public sources I linked.', 'I understand the report is filed as a public GitHub issue, and that Sieve shows only reports the maintainer has reviewed and accepted.'].map((t, i) => (
                  <label key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 13 }}>
                    <input type="checkbox" style={{ marginTop: 2 }} checked={agree[i]} onChange={() => setAgree(a => a.map((v, j) => (j === i ? !v : v)))} />{t}
                  </label>
                ))}
              </div>
              {tried && problems.length > 0 && (
                <div className="callout" role="alert" style={{ borderColor: 'var(--amber)' }}><Icon name="warn" size={18} /><span>{problems.join(' ')}</span></div>
              )}
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                <button type="submit" className="btn pri">Submit report</button>
                <span className="small muted">Opens GitHub with your report filled in, to send it from your account. You can also use <a href={REPORT_FORM} target="_blank" rel="noopener">the form on GitHub</a> directly.</span>
              </div>
            </form>
          )}
        </section>
      </main>
      <Footer />
    </>
  );
}
