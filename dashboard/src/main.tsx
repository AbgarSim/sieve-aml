import React from 'react';
import ReactDOM from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import App from './App';
import { ThemeProvider } from './lib/theme';
import { loadSnapshot } from './data/snapshot';
import './styles.css';

const root = ReactDOM.createRoot(document.getElementById('root')!);

loadSnapshot()
  .then(() => root.render(
    <React.StrictMode>
      <ThemeProvider>
        <HashRouter>
          <App />
        </HashRouter>
      </ThemeProvider>
    </React.StrictMode>,
  ))
  .catch((e: unknown) => root.render(
    <main className="wrap page">
      <div className="card empty">
        <h3>Snapshot unavailable</h3>
        <p>The nightly data could not be loaded ({e instanceof Error ? e.message : String(e)}). Try again later.</p>
      </div>
    </main>,
  ));
