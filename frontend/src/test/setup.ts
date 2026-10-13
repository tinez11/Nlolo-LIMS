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

// jsdom has no scrollIntoView, which every browser does. listKeys calls it as j/k move through a
// list, so listKeys.test.tsx threw seven uncaught errors on every run -- and an uncaught error makes
// vitest exit 1 with every test passing, which is a red run nobody could tell from a real one.
Element.prototype.scrollIntoView = function scrollIntoView() {};
