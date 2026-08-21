import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BeneficiaryForm } from './beneficiary-form';

const ONE = [{ freeformDesignee: 'Asha Juma', sharePercentage: '100' }];

describe('BeneficiaryForm', () => {
  it('blocks submission when shares do not sum to 100', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<BeneficiaryForm initial={[{ freeformDesignee: 'Asha Juma', sharePercentage: '60' }]} onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText(/must add up to 100/i)).toBeInTheDocument();
  });

  it('submits when shares sum to exactly 100', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<BeneficiaryForm initial={ONE} onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('rejects a beneficiary with both partyId and freeformDesignee', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<BeneficiaryForm
      initial={[{ partyId: '11111111-1111-1111-1111-111111111111', freeformDesignee: 'Asha', sharePercentage: '100' }]}
      onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText(/either a registered person or a name, not both/i)).toBeInTheDocument();
  });

  it('surfaces a backend 422 as readable copy', async () => {
    const onSave = vi.fn().mockRejectedValue({ status: 422, problem: { status: 422, errorCode: 'VALIDATION_ERROR', traceId: 't' } });
    render(<BeneficiaryForm initial={ONE} onSave={onSave} />);

    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    expect(await screen.findByText(/not valid/i)).toBeInTheDocument();
  });
});
