import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, Link, MemoryRouter, RouterProvider } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { UnsavedGuard } from './UnsavedGuard';

function renderForm(dirty: boolean) {
  const router = createMemoryRouter(
    [
      {
        path: '/form',
        element: (
          <>
            <h1>The form</h1>
            <UnsavedGuard when={dirty} what="The product version you were building" />
            <Link to="/elsewhere">Policies</Link>
            <Link to="/form?tab=rates">Rates tab</Link>
          </>
        ),
      },
      { path: '/elsewhere', element: <h1>Elsewhere</h1> },
    ],
    { initialEntries: ['/form'] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('UnsavedGuard', () => {
  it('stops a sidebar click on unsaved work, and asks', async () => {
    const user = userEvent.setup();
    renderForm(true);
    await user.click(screen.getByRole('link', { name: 'Policies' }));
    expect(screen.getByRole('heading', { name: 'The form' })).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).toHaveTextContent(
      'The product version you were building is not saved',
    );
    // Focus goes to the safe answer, so Enter does not throw the work away.
    expect(screen.getByRole('button', { name: 'Keep editing' })).toHaveFocus();
  });

  it('keeps the work when asked to, and leaves when asked to', async () => {
    const user = userEvent.setup();
    const router = renderForm(true);
    await user.click(screen.getByRole('link', { name: 'Policies' }));
    await user.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/form');

    await user.click(screen.getByRole('link', { name: 'Policies' }));
    await user.click(screen.getByRole('button', { name: 'Leave' }));
    expect(await screen.findByRole('heading', { name: 'Elsewhere' })).toBeInTheDocument();
  });

  it('lets clean work go without asking', async () => {
    const user = userEvent.setup();
    renderForm(false);
    await user.click(screen.getByRole('link', { name: 'Policies' }));
    expect(await screen.findByRole('heading', { name: 'Elsewhere' })).toBeInTheDocument();
  });

  // Changing tab is the same page seen differently, not leaving it.
  it('does not stand in the way of a change on the same page', async () => {
    const user = userEvent.setup();
    const router = renderForm(true);
    await user.click(screen.getByRole('link', { name: 'Rates tab' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(router.state.location.search).toBe('?tab=rates');
  });

  // Component tests render inside a plain MemoryRouter, where useBlocker would throw.
  it('renders nothing, and does not throw, outside a data router', () => {
    const { container } = render(
      <MemoryRouter>
        <UnsavedGuard when what="Anything" />
      </MemoryRouter>,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
