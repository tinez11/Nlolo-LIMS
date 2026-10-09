import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Tip } from './tooltip';

describe('Tip', () => {
  // The whole reason it replaces `title`: a keyboard user reaches it.
  it('opens when its trigger takes keyboard focus', async () => {
    const user = userEvent.setup();
    render(
      <Tip content="Waiting on the gateway's receipt" focusable>
        <span>Accepted</span>
      </Tip>,
    );
    await user.tab();
    expect(await screen.findByRole('tooltip')).toHaveTextContent("Waiting on the gateway's receipt");
  });

  it('adds no tab stop unless asked to', async () => {
    const user = userEvent.setup();
    render(
      <>
        <Tip content="Explained">
          <span>Plain</span>
        </Tip>
        <button type="button">Next</button>
      </>,
    );
    await user.tab();
    expect(screen.getByRole('button', { name: 'Next' })).toHaveFocus();
  });
});
