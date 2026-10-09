import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// Inter, self-hosted as one variable file with its optical-size axis (2026-10-09). It was fetched
// from Google's font CDN: a white-label console hosted per tenant made a third-party request for its
// only typeface on every load, could not work offline in dev, and had static 400/500/600 only, so the
// gate marker's 700 was a browser-faked bold. `opsz` lets the letterforms tighten for headings and
// open up at 12px on their own.
import '@fontsource-variable/inter/opsz.css';
import './index.css';
import { App } from './App';

const container = document.getElementById('root');
if (!container) throw new Error('#root missing from index.html');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
