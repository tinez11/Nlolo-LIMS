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

  it('marks the first section as current before anything has scrolled', () => {
    render(
      <SectionNav
        label="Claim sections"
        sections={[
          { id: 'assessment', label: 'Assessment' },
          { id: 'evidence', label: 'Evidence' },
        ]}
      />,
    );
    expect(screen.getByRole('link', { name: 'Assessment' })).toHaveAttribute(
      'aria-current',
      'true',
    );
    expect(screen.getByRole('link', { name: 'Evidence' })).not.toHaveAttribute('aria-current');
  });

  it('publishes its height so the panels and the rail can stop under it', () => {
    const { unmount } = render(
      <SectionNav label="Claim sections" sections={[{ id: 'assessment', label: 'Assessment' }]} />,
    );
    // jsdom reports every offsetHeight as 0, so the VALUE proves nothing here -- that the
    // property is set at all, and removed again on unmount, is the contract worth holding. A
    // wrong height shows up in a screenshot; a property left behind on the next page does not.
    expect(document.documentElement.style.getPropertyValue('--sectionbar-h')).toBe('0px');
    unmount();
    expect(document.documentElement.style.getPropertyValue('--sectionbar-h')).toBe('');
  });
});
