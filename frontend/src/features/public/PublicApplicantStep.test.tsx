import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as publicApi from '@/api/publicQuote';
import { useLanguageStore } from '@/features/customer/language';
import { PublicApplicantStep, PublicDeclaration, type PublicApplicant } from './PublicApplicantStep';

vi.mock('@/api/publicQuote');

// Read in Swahili, as most visitors will (the platform's default language for customers).
beforeEach(() => {
  vi.clearAllMocks();
  useLanguageStore.getState().setLanguage('sw');
});

function Harness() {
  const [value, setValue] = useState<PublicApplicant>({ fullName: 'Neema Mushi', email: '', confirmed: null });
  return <PublicApplicantStep visitToken="visit-1" initialPhone="0713 555 101" value={value} onChange={setValue} />;
}

/** 2026-11-02, the user: applying with no login needs the phone, confirmed by a texted code. */
describe('confirming the phone on the public link', () => {
  it('texts a code to the number given and confirms it when typed back', async () => {
    vi.mocked(publicApi.sendPublicCode).mockResolvedValue(undefined);
    vi.mocked(publicApi.checkPublicCode).mockResolvedValue({ phoneNumber: '+255713555101', phoneToken: 'proof' });
    render(<Harness />);

    await userEvent.click(screen.getByRole('button', { name: 'Nitumie namba' }));
    expect(publicApi.sendPublicCode).toHaveBeenCalledWith('0713 555 101', 'SW', 'visit-1');
    expect(await screen.findByText(/Tumetuma namba ya tarakimu 6 kwa 0713 555 101/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Namba kutoka kwenye ujumbe/), '12a3456');
    await userEvent.click(screen.getByRole('button', { name: 'Thibitisha namba yangu' }));
    expect(publicApi.checkPublicCode).toHaveBeenCalledWith('0713 555 101', '123456', 'SW', 'visit-1');
    expect(await screen.findByText('Simu imethibitishwa: +255713555101')).toBeInTheDocument();
  });

  it('says why a code was refused, in the words the server gives', async () => {
    vi.mocked(publicApi.sendPublicCode).mockRejectedValue({
      status: 422, kind: 'validation', title: 'Phone not confirmed', detail: 'Tumetuma namba mara tatu ndani ya saa moja.',
      traceId: 't', errorCode: 'PHONE_CODE_REFUSED', fieldErrors: [], mayBeDenied: false,
    });
    render(<Harness />);

    await userEvent.click(screen.getByRole('button', { name: 'Nitumie namba' }));
    expect(await screen.findByText(/mara tatu ndani ya saa moja/)).toBeInTheDocument();
  });
});

describe('the declaration', () => {
  it('shows the wording in the language read and records the box ticked', async () => {
    vi.mocked(publicApi.getPublicConsent).mockResolvedValue({
      version: 'V-1', en: 'Before you send:\n1. True answers.', sw: 'Kabla ya kutuma:\n1. Majibu ya kweli.',
    });
    const onAgree = vi.fn();
    render(<PublicDeclaration agreed={false} onAgree={onAgree} onWording={() => undefined} />);

    expect(await screen.findByText('1. Majibu ya kweli.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('checkbox', { name: 'Nimesoma haya na ninakubali' }));
    expect(onAgree).toHaveBeenCalledWith(true);
  });
});
