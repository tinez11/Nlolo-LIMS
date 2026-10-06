import { describe, expect, it } from 'vitest';
import { PORTFOLIO_CODES, bucketLabel, channelLabel, portfolioDefaultFor, portfolioLabel } from './ifrs17';

describe('the IFRS 17 vocabulary', () => {
  it('defaults each category to the portfolio the server would', () => {
    expect(portfolioDefaultFor('TERM_LIFE')).toBe('TERM');
    expect(portfolioDefaultFor('EDUCATION_SAVINGS')).toBe('END');
    expect(portfolioDefaultFor('ANNUITY')).toBe('IANN');
    expect(portfolioDefaultFor('CREDIT_LIFE')).toBe('CRL');
    expect(portfolioDefaultFor('FUNERAL')).toBe('FUN');
  });

  it('lists the fourteen portfolios of the spec', () => {
    expect(PORTFOLIO_CODES).toHaveLength(14);
    expect(PORTFOLIO_CODES).toContain('PAR');
  });

  it('labels in plain words and falls back to the code, or a dash for nothing', () => {
    expect(portfolioLabel('MB')).toBe('Money-back');
    expect(portfolioLabel('XYZ')).toBe('XYZ');
    expect(portfolioLabel(null)).toBe('—');
    expect(bucketLabel('REMAINING')).toBe('Remaining');
    expect(channelLabel('BANCASSURANCE')).toBe('Bancassurance');
  });
});
