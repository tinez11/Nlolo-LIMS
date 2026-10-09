import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { forgetAll } from '@/lib/remembered';

// The tab cache is module state: without this, one test's answer for POL-1 would seed the next
// test that renders POL-1, and a test would pass or fail by the order it ran in.
afterEach(() => forgetAll());

// polyfill for cmdk / other components that rely on ResizeObserver
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};
