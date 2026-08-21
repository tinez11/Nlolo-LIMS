import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { useSubmitGuard } from './use-submit-guard';

function Form({ onSubmit }: { onSubmit: () => Promise<void> }) {
  const { submit, isSubmitting } = useSubmitGuard(onSubmit);
  return (
    <button
      type="button"
      disabled={isSubmitting}
      // .catch swallows the deliberately-rejected onSubmit in the retry test below so it doesn't
      // surface as an unhandled rejection; useSubmitGuard itself is unchanged and still propagates
      // the rejection to its caller, exactly as specified.
      onClick={() => { void submit().catch(() => {}); }}
    >
      Submit
    </button>
  );
}

describe('useSubmitGuard', () => {
  it('fires once when two clicks land in the same tick', async () => {
    // THE test for this hook. A useState-driven `disabled` passes nothing here: React batches the
    // state update, so both handlers run before either re-render. Only a synchronous ref check
    // set before the first await survives this.
    const onSubmit = vi.fn().mockImplementation(() => new Promise<void>((r) => setTimeout(r, 10)));
    render(<Form onSubmit={onSubmit} />);
    const button = screen.getByRole('button');

    button.click();
    button.click();
    button.click();

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('allows a second submit after the first settles', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<Form onSubmit={onSubmit} />);
    const button = screen.getByRole('button');

    button.click();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    button.click();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });

  it('re-arms after a rejected submit so the user can retry', async () => {
    const onSubmit = vi.fn()
      .mockRejectedValueOnce(new Error('validation'))
      .mockResolvedValueOnce(undefined);
    render(<Form onSubmit={onSubmit} />);
    const button = screen.getByRole('button');

    button.click();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    button.click();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });
});
