import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { InlineError } from './InlineError';

describe('InlineError', () => {
  it('announces the detail, falling back to the title', () => {
    const { rerender } = render(
      <InlineError error={{ title: 'Conflict', detail: 'The policy has lapsed.', traceId: null }} />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('The policy has lapsed.');
    rerender(<InlineError error={{ title: 'Conflict', detail: null, traceId: null }} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Conflict');
  });

  it('prints the trace id at full strength, labelled, and selectable', () => {
    render(<InlineError error={{ title: 'Conflict', detail: null, traceId: 'abc123' }} />);
    const trace = screen.getByText('abc123');
    expect(trace).toHaveClass('select-all');
    expect(trace.className).not.toMatch(/opacity|text-\[1[01]px\]/);
    expect(screen.getByRole('alert')).toHaveTextContent('Reference abc123');
  });

  it('names the act before the server words, when the caller gives one', () => {
    render(
      <InlineError
        lead="Could not mark it matched"
        error={{ title: 'Conflict', detail: 'Already reconciled.', traceId: null }}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Could not mark it matched — Already reconciled.',
    );
  });

  it('renders extra lines a caller needs below the message', () => {
    render(
      <InlineError error={{ title: 'Conflict', detail: null, traceId: null }}>
        <p>Nothing was saved.</p>
      </InlineError>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Nothing was saved.');
  });

  // ---- Goes away as the form is corrected (2026-10-20) --------------------------------------------------------

  const refused = { title: 'Conflict', detail: 'Client reference BLIC001 already belongs to another client', traceId: 't1' };

  it('goes away on the first edit to its form, without a second submit', async () => {
    render(
      <form>
        <input aria-label="Client reference" />
        <InlineError error={refused} />
      </form>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Client reference'), 'X');

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('comes back for a new refusal -- a fresh answer from the server, even in the same words', async () => {
    const { rerender } = render(
      <form>
        <input aria-label="Client reference" />
        <InlineError error={refused} />
      </form>,
    );
    await userEvent.type(screen.getByLabelText('Client reference'), 'X');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    rerender(
      <form>
        <input aria-label="Client reference" />
        <InlineError error={{ ...refused, traceId: 't2' }} />
      </form>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('stays while the same refusal is re-rendered as a new object, once dismissed', async () => {
    const { rerender } = render(
      <form>
        <input aria-label="Name" />
        <InlineError error={{ ...refused }} />
      </form>,
    );
    await userEvent.type(screen.getByLabelText('Name'), 'X');
    rerender(
      <form>
        <input aria-label="Name" />
        <InlineError error={{ ...refused }} />
      </form>,
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a sticky error -- a failed read, not a refusal -- stays however the form is edited', async () => {
    render(
      <form>
        <input aria-label="Name" />
        <InlineError sticky error={{ title: 'Not found', detail: 'The product version could not be read', traceId: null }} />
      </form>,
    );
    await userEvent.type(screen.getByLabelText('Name'), 'X');

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('an edit in a different form leaves it alone', async () => {
    render(
      <>
        <form>
          <InlineError error={refused} />
        </form>
        <form>
          <input aria-label="Elsewhere" />
        </form>
      </>,
    );
    await userEvent.type(screen.getByLabelText('Elsewhere'), 'X');

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
