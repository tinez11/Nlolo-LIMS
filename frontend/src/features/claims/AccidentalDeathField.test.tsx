import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as funeralApi from '@/api/funeral';
import type { ClaimView } from '@/api/types';
import { AccidentalDeathField } from './AccidentalDeathField';

vi.mock('@/api/funeral');

beforeEach(() => vi.clearAllMocks());

describe('AccidentalDeathField', () => {
  it('lets claims staff record a natural death as accidental, then reloads the claim', async () => {
    vi.mocked(funeralApi.recordAccidentalDeath).mockResolvedValue({} as ClaimView);
    const onChanged = vi.fn();
    render(<dl><AccidentalDeathField claimId="c-1" accidental={false} canChange onChanged={onChanged} /></dl>);

    expect(screen.getByText('Natural')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Record as accidental' }));

    expect(funeralApi.recordAccidentalDeath).toHaveBeenCalledWith('c-1', true);
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('shows the cause without the switch once the claim is decided, or to anyone else', () => {
    render(<dl><AccidentalDeathField claimId="c-1" accidental canChange={false} onChanged={vi.fn()} /></dl>);
    expect(screen.getByText('Accidental')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('shows the refusal in the server words', async () => {
    vi.mocked(funeralApi.recordAccidentalDeath).mockRejectedValue({
      status: 409, kind: 'conflict', errorCode: null, title: 'Conflict', detail: 'Claim c-1 is APPROVED',
      traceId: 't', fieldErrors: [], mayBeDenied: false,
    });
    render(<dl><AccidentalDeathField claimId="c-1" accidental={false} canChange onChanged={vi.fn()} /></dl>);
    await userEvent.click(screen.getByRole('button', { name: 'Record as accidental' }));
    expect(await screen.findByText(/Claim c-1 is APPROVED/)).toBeInTheDocument();
  });
});
