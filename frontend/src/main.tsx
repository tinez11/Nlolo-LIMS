import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// Manrope for every working line, Sora for the headings (the Tinezz direction, 2026-10-21). Both
// self-hosted as variable files, as Inter was before them: a white-label console hosted per tenant
// makes no third-party request for its typefaces, and every weight is real rather than faked.
import '@fontsource-variable/manrope/index.css';
import '@fontsource-variable/sora/index.css';
import './index.css';
import { App } from './App';

const container = document.getElementById('root');
if (!container) throw new Error('#root missing from index.html');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
