import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { RecordTabs } from './RecordTabs';

const TABS = [
  { value: 'overview', label: 'Overview', content: <p>Overview body</p> },
  { value: 'billing', label: 'Billing', count: 12, content: <p>Billing body</p> },
];

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.search}</output>;
}

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <RecordTabs tabs={TABS} label="Policy sections" />
      <Where />
    </MemoryRouter>,
  );
}

describe('RecordTabs', () => {
  it('opens on the first tab with no parameter, and mounts only that tab', () => {
    renderAt('/p');
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('Overview body')).toBeInTheDocument();
    // Unmounted, not hidden: a tab's data should load when it is opened, not on arrival.
    expect(screen.queryByText('Billing body')).not.toBeInTheDocument();
  });

  it('opens the tab the URL names', () => {
    renderAt('/p?tab=billing');
    expect(screen.getByText('Billing body')).toBeInTheDocument();
  });

  it('falls back to the first tab for a name it does not know', () => {
    renderAt('/p?tab=nonsense');
    expect(screen.getByText('Overview body')).toBeInTheDocument();
  });

  it('writes the chosen tab into the URL, and removes it for the first tab', async () => {
    renderAt('/p?x=1');
    await userEvent.click(screen.getByRole('tab', { name: /Billing/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('?x=1&tab=billing');
    await userEvent.click(screen.getByRole('tab', { name: 'Overview' }));
    expect(screen.getByTestId('where')).toHaveTextContent('?x=1');
  });

  it('says what a count counts', () => {
    renderAt('/p');
    expect(screen.getByRole('tab', { name: 'Billing, 12 items' })).toBeInTheDocument();
  });
});
