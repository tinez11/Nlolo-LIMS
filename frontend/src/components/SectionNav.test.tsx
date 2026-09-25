import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SectionNav } from './SectionNav';

describe('SectionNav', () => {
  it('is a named navigation of in-page links', () => {
    render(
      <SectionNav
        label="Claim sections"
        sections={[
          { id: 'claim-decision', label: 'Decision' },
          { id: 'claim-evidence', label: 'Evidence' },
        ]}
      />,
    );
    const nav = screen.getByRole('navigation', { name: 'Claim sections' });
    expect(nav).toHaveClass('sticky');
    expect(screen.getByRole('link', { name: 'Evidence' })).toHaveAttribute(
      'href',
      '#claim-evidence',
    );
  });
});
