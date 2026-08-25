import '@testing-library/jest-dom/vitest';

// polyfill for cmdk / other components that rely on ResizeObserver
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};
