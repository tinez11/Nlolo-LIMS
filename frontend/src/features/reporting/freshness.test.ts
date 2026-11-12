import { describe, expect, it } from 'vitest';
import { freshnessLine, sourcesInWords } from './freshness';

describe('freshnessLine', () => {
  it('says the last change the copy took in, and what is read live beside it', () => {
    const line = freshnessLine(['REPORTING_COPY', 'BILLING'], '2026-11-11T14:25:00Z');
    expect(line).toMatch(/^Last change taken in: .+ · billing read live$/);
  });

  it('says a ledger report reads live', () => {
    expect(freshnessLine(['LEDGER'], null)).toBe('Read live from the ledger');
  });

  it('says so when the copy has taken in nothing yet', () => {
    expect(freshnessLine(['REPORTING_COPY'], null)).toBe('Nothing taken in yet');
  });
});

describe('sourcesInWords', () => {
  it('names the copy and the live sources in plain words', () => {
    expect(sourcesInWords(['REPORTING_COPY', 'BILLING'])).toBe("the reports' own copy, updated as things happen and billing");
    expect(sourcesInWords(['DISTRIBUTION', 'REPORTING_COPY'])).toBe(
      "agents and commission and the reports' own copy, updated as things happen");
  });
});
