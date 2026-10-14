import { render, screen } from '@testing-library/react';
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

  it('keeps the reference off a refusal the message already explains', () => {
    // A business rule said no and said why. The id underneath read as a system fault on a
    // screen where nothing is broken -- and is the first thing an audience asks about.
    render(
      <InlineError
        error={{
          kind: 'unprocessable',
          title: 'Unprocessable',
          detail: 'Separation of duties: you assessed this claim, so you cannot also decide it.',
          traceId: 'abc123',
        }}
      />,
    );
    expect(screen.getByRole('alert')).not.toHaveTextContent('Reference');
    expect(screen.queryByText('abc123')).not.toBeInTheDocument();
  });

  it('keeps the reference on a failure nobody can explain from the screen', () => {
    render(
      <InlineError
        error={{ kind: 'server', title: 'Server error', detail: 'Something failed.', traceId: 'abc123' }}
      />,
    );
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
});
