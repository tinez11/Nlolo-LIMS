import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
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

  it('keeps the way back when a tab is chosen -- a policy opened from its client stays under that client', async () => {
    function Trail() {
      const state = useLocation().state as { cameFrom?: { label: string }[] } | null;
      return <output data-testid="trail">{state?.cameFrom?.map((c) => c.label).join(' > ') ?? 'none'}</output>;
    }
    const trail = [{ label: 'Individuals', to: '/staff/clients' }, { label: 'Martin Lema', to: '/staff/clients/p-1' }];
    render(
      <MemoryRouter initialEntries={[{ pathname: '/p', state: { cameFrom: trail } }]}>
        <RecordTabs tabs={TABS} label="Policy sections" />
        <Trail />
      </MemoryRouter>,
    );
    await userEvent.click(screen.getByRole('tab', { name: /Billing/ }));
    expect(screen.getByTestId('trail')).toHaveTextContent('Individuals > Martin Lema');
    await userEvent.click(screen.getByRole('tab', { name: 'Overview' }));
    expect(screen.getByTestId('trail')).toHaveTextContent('Individuals > Martin Lema');
  });

  it('says what a count counts', () => {
    renderAt('/p');
    expect(screen.getByRole('tab', { name: 'Billing, 12 items' })).toBeInTheDocument();
  });

  /*
    Opening a tab from halfway down a record used to leave the scroll where it was.
    The panel that was there is unmounted, so the new one mounts at the TOP of a
    container the reader is scrolled past -- and on a `DetailLayout` page the sticky
    tab strip leaves with it, because the strip sticks within the tabs' own box and
    that box can be far shorter than the record rail beside it. The reader lands on
    the bottom of the rail, seeing neither the section they asked for nor the tabs.

    jsdom implements no scrolling, so these assert the call rather than a pixel --
    which is also the honest limit of what a unit test can say here.
  */
  describe('returning to the top of the section', () => {
    // Typed with scrollIntoView's own signature: the vitest typings on main (newer than this test's 2026-10-14 cut)
    // no longer let a bare `vi.fn()` stand in for a DOM method.
    let scrollIntoView: Mock<(arg?: boolean | ScrollIntoViewOptions) => void>;

    beforeEach(() => {
      scrollIntoView = vi.fn<(arg?: boolean | ScrollIntoViewOptions) => void>();
      Element.prototype.scrollIntoView = scrollIntoView;
    });

    afterEach(() => {
      vi.restoreAllMocks();
    });

    /** Where the strip sits when a tab is opened: scrolled past (above the page bar), or in plain view. */
    function stripAt(top: number) {
      vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ top } as DOMRect);
    }

    it('returns to the tab strip when a tab is opened from a scrolled-past strip', async () => {
      renderAt('/p');
      stripAt(-600);
      await userEvent.click(screen.getByRole('tab', { name: /Billing/ }));
      expect(screen.getByText('Billing body')).toBeInTheDocument();
      expect(scrollIntoView).toHaveBeenCalled();
    });

    // The user, 2026-10-22: a tab clicked in plain view moved the page.
    it('leaves the page where it is when the strip is already in view', async () => {
      renderAt('/p');
      stripAt(120);
      await userEvent.click(screen.getByRole('tab', { name: /Billing/ }));
      expect(screen.getByText('Billing body')).toBeInTheDocument();
      expect(scrollIntoView).not.toHaveBeenCalled();
    });

    // A deep link and a first paint are someone ARRIVING, not switching. Scrolling
    // them would fight the browser's own restoration and move a page nobody moved.
    it('does not scroll on first render, including straight to a named tab', () => {
      renderAt('/p?tab=billing');
      expect(screen.getByText('Billing body')).toBeInTheDocument();
      expect(scrollIntoView).not.toHaveBeenCalled();
    });
  });
});
