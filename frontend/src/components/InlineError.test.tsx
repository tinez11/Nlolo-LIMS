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
