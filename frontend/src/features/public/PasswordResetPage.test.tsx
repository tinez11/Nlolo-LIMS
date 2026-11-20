import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as resetApi from '@/api/passwordReset';
import * as publicApi from '@/api/publicQuote';
import { useLanguageStore } from '@/features/customer/language';
import { PasswordResetPage } from './PasswordResetPage';

vi.mock('@/api/passwordReset');
vi.mock('@/api/publicQuote');
// Turnstile passes at once in tests: what is being tested is the page, not Cloudflare.
vi.mock('@/components/Turnstile', () => ({
  Turnstile: ({ onToken }: { onToken: (t: string) => void }) => {
    queueMicrotask(() => onToken('turnstile-ok'));
    return null;
  },
}));

const assign = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(publicApi.startPublicVisit).mockResolvedValue({ visitToken: 'visit-1', minutes: 30 });
  Object.defineProperty(window, 'location', { value: { ...window.location, assign }, writable: true });
});

function renderPage() {
  return render(<MemoryRouter><PasswordResetPage /></MemoryRouter>);
}

/** 2026-11-19 (Authentik cut-over ticket 07): "Forgot your password?" for agents and customers, in two languages. */
describe('the password reset page', () => {
  it('texts a code, takes it on the same page, and sends the browser to choose a new password', async () => {
    useLanguageStore.getState().setLanguage('en');
    vi.mocked(resetApi.requestResetCode).mockResolvedValue(undefined);
    vi.mocked(resetApi.checkResetCode).mockResolvedValue({ link: 'http://localhost:9100/if/flow/lifeplatform-set-password/?flow_token=t' });
    renderPage();

    await userEvent.type(await screen.findByLabelText(/Your phone number/), '0713 000 101');
    await userEvent.click(screen.getByRole('button', { name: 'Send me a code' }));
    expect(resetApi.requestResetCode).toHaveBeenCalledWith('0713 000 101', 'en', 'visit-1');
    // The same answer whether or not the number has a login.
    expect(await screen.findByText("If this number has a login, we've texted a code.")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Code from the text/), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(resetApi.checkResetCode).toHaveBeenCalledWith('0713 000 101', '123456', 'en', 'visit-1');
    expect(assign).toHaveBeenCalledWith('http://localhost:9100/if/flow/lifeplatform-set-password/?flow_token=t');
  });

  it('reads in Kiswahili', async () => {
    useLanguageStore.getState().setLanguage('sw');
    vi.mocked(resetApi.requestResetCode).mockResolvedValue(undefined);
    renderPage();

    await userEvent.type(await screen.findByLabelText(/Namba yako ya simu/), '0713000101');
    await userEvent.click(screen.getByRole('button', { name: 'Nitumie namba' }));
    expect(resetApi.requestResetCode).toHaveBeenCalledWith('0713000101', 'sw', 'visit-1');
    expect(await screen.findByText('Kama namba hii ina akaunti, tumeituma namba ya kuthibitisha.')).toBeInTheDocument();
  });

  it("says why a code was refused, in the server's words", async () => {
    useLanguageStore.getState().setLanguage('en');
    vi.mocked(resetApi.requestResetCode).mockRejectedValue({
      status: 429, kind: 'unknown', title: 'Too many codes', detail: 'Too many codes for this number. Try again in an hour.',
      traceId: 't', errorCode: 'RESET_RATE_LIMITED', fieldErrors: [], mayBeDenied: false,
    });
    renderPage();

    await userEvent.type(await screen.findByLabelText(/Your phone number/), '0713000101');
    await userEvent.click(screen.getByRole('button', { name: 'Send me a code' }));
    expect(await screen.findByText('Too many codes for this number. Try again in an hour.')).toBeInTheDocument();
  });
});
