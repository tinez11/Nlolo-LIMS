import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RepayForm } from './page';

/**
 * Regression coverage for C2: `clientKey` must rotate ONLY after a confirmed success, never
 * after a correctable rejection. `RepayForm` calls `fetch` directly (there is no injectable
 * onSave-style prop for the mutation itself, unlike `BeneficiaryForm`), so the mock target here
 * is the global `fetch` the component actually calls, with the `clientKey` read back out of the
 * request body it sent -- the only place the rotation decision is externally observable.
 */
function jsonResponse(body: unknown, init: { ok: boolean; status: number }): Response {
  return {
    ok: init.ok,
    status: init.status,
    json: async () => body,
  } as Response;
}

function requestBody(fetchMock: ReturnType<typeof vi.fn>, callIndex: number): { clientKey: string } {
  const [, requestInit] = fetchMock.mock.calls[callIndex];
  return JSON.parse(requestInit.body as string);
}

async function fillAndSubmit(amount: string, reference: string) {
  await userEvent.clear(screen.getByLabelText(/repayment amount/i));
  await userEvent.type(screen.getByLabelText(/repayment amount/i), amount);
  await userEvent.clear(screen.getByLabelText(/payment reference/i));
  await userEvent.type(screen.getByLabelText(/payment reference/i), reference);
  await userEvent.click(screen.getByRole('button', { name: /make repayment/i }));
}

describe('RepayForm', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('rotates clientKey after a confirmed success, so a second repayment is a new claim', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({}, { ok: true, status: 200 }))
      .mockResolvedValueOnce(jsonResponse({}, { ok: true, status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    render(<RepayForm loanId="loan-1" onRepaid={vi.fn()} />);

    await fillAndSubmit('10000', 'ref-1');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    await fillAndSubmit('20000', 'ref-2');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    const firstKey = requestBody(fetchMock, 0).clientKey;
    const secondKey = requestBody(fetchMock, 1).clientKey;
    expect(secondKey).not.toBe(firstKey);
  });

  it('keeps the SAME clientKey after a correctable rejection, preserving the retry as ' +
    'one idempotency claim (Layer 2 release-and-retry semantics)', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(
        { errorCode: 'VALIDATION_ERROR', status: 422, title: 'Invalid', traceId: 't1' },
        { ok: false, status: 422 },
      ))
      .mockResolvedValueOnce(jsonResponse({}, { ok: true, status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    render(<RepayForm loanId="loan-1" onRepaid={vi.fn()} />);

    await fillAndSubmit('10000', 'ref-1');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    // The rejection surfaces as readable copy, not a silent no-op.
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    // Customer corrects the input and retries.
    await fillAndSubmit('15000', 'ref-1-corrected');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    const firstKey = requestBody(fetchMock, 0).clientKey;
    const secondKey = requestBody(fetchMock, 1).clientKey;
    expect(secondKey).toBe(firstKey);
  });
});
