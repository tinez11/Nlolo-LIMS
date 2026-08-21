// Vitest runs in jsdom, where the real `server-only` package throws on import. The build-time
// guarantee that matters comes from `next build`, which uses the real package.
export {};
