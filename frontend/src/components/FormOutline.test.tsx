import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { FormOutline } from './FormOutline';

function Page() {
  const [annuity, setAnnuity] = useState(false);
  return (
    <>
      <form id="version-form">
        <div data-outline="Basics">basics</div>
        <div data-outline="Benefit schedule">benefits</div>
        {annuity && <div data-outline="Annuity terms">annuity</div>}
        <button type="button" onClick={() => setAnnuity(true)}>Make it an annuity</button>
      </form>
      <FormOutline containerId="version-form" label="Version form sections" />
    </>
  );
}

describe('FormOutline', () => {
  it('lists the sections the form actually has, and follows them as they change', async () => {
    const user = userEvent.setup();
    render(<Page />);
    const outline = await screen.findByRole('navigation', { name: 'Version form sections' });
    expect(outline).toHaveTextContent('Basics');
    expect(outline).toHaveTextContent('Benefit schedule');
    expect(outline).not.toHaveTextContent('Annuity terms');

    await user.click(screen.getByRole('button', { name: 'Make it an annuity' }));
    expect(await screen.findByRole('button', { name: 'Annuity terms' })).toBeInTheDocument();
  });

  it('takes you to a section when you choose it', async () => {
    const user = userEvent.setup();
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    render(<Page />);
    await user.click(await screen.findByRole('button', { name: 'Benefit schedule' }));
    expect(scroll).toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Benefit schedule' })).toHaveAttribute('aria-current', 'true');
  });

  it('draws nothing for a form of one section', async () => {
    render(
      <>
        <form id="short">
          <div data-outline="Only">x</div>
        </form>
        <FormOutline containerId="short" label="Short form" />
      </>,
    );
    await act(() => new Promise((r) => requestAnimationFrame(() => r(null))));
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
