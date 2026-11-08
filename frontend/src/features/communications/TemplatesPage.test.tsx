import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/api/communications';
import type { NotificationTemplateView } from '@/api/types';
import { idle } from '@/store/createResourceSlice';
import { useCommunicationsStore } from '@/store/communicationsStore';
import { grantAccess } from '@/test/access';
import { renderScreen as render } from '@/test/renderScreen';
import { TemplatesPage } from './TemplatesPage';

vi.mock('@/api/communications');

const template = (channel: string, language: string, body: string): NotificationTemplateView => ({
  templateId: `t-${channel}-${language}`, templateKey: 'ACCOUNT_STATEMENT', channel, language, bodyTemplate: body,
  placeholders: ['policyNumber', 'year', 'balance', 'interest'],
} as NotificationTemplateView);

const SW_SMS = 'Bima {{policyNumber}}: salio la akiba tarehe 31/12/{{year}} ni {{balance}}. Riba {{year}}: {{interest}}.';
const EN_SMS = 'Policy {{policyNumber}}: your savings balance on 31/12/{{year}} is {{balance}}. Interest: {{interest}}.';

/** 2026-11-08, the user: "not user feasible" -- the raw template was the only thing to read. */
describe('TemplatesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useCommunicationsStore.setState({ templates: idle(), rewording: idle() });
    vi.mocked(api.listTemplates).mockResolvedValue([template('SMS', 'sw', SW_SMS), template('SMS', 'en', EN_SMS)]);
    vi.mocked(api.getChannels).mockResolvedValue([{ channel: 'SMS', mode: 'OFF' }, { channel: 'EMAIL', mode: 'TEST' }]);
  });

  it('says plainly whether customers are reached, and reads each message as the customer will', async () => {
    grantAccess('communications.view');
    render(<TemplatesPage />);

    expect(await screen.findByText(/off — not reaching customers/)).toBeInTheDocument();
    const card = await screen.findByRole('region', { name: 'Yearly savings statement' });
    expect(within(card).getByText(/once a year, after interest is credited/)).toBeInTheDocument();
    expect(within(card).getByLabelText('Preview')).toHaveTextContent('Bima POL-3E440FE9: salio la akiba tarehe 31/12/2026 ni TZS 2,005,000.00');
    expect(within(card).getByText(/1 SMS/)).toBeInTheDocument();

    await userEvent.click(within(card).getByRole('tab', { name: 'English' }));
    expect(within(card).getByLabelText('Preview')).toHaveTextContent('Policy POL-3E440FE9: your savings balance');
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('warns before a value is dropped, and sends the editor a test', async () => {
    grantAccess('communications.view', 'communications.templates');
    vi.mocked(api.sendTestMessage).mockResolvedValue({ outcome: 'NOT_SENT', detail: 'SMS sending is switched off', body: 'x' });
    const user = userEvent.setup();
    render(<TemplatesPage />);

    await user.click(await screen.findByRole('button', { name: 'Edit' }));
    const box = screen.getByLabelText(/wording$/);
    await user.clear(box);
    await user.type(box, 'Bima ya akiba ');
    expect(screen.getByRole('alert')).toHaveTextContent('no longer includes Policy number, Year, Balance, Interest');

    await user.click(screen.getByRole('button', { name: 'Policy number' }));
    expect(box).toHaveValue('Bima ya akiba {{policyNumber}}');

    await user.type(screen.getByLabelText('Your phone number'), '+255712000001');
    await user.click(screen.getByRole('button', { name: 'Send test' }));
    await waitFor(() => expect(api.sendTestMessage).toHaveBeenCalledWith('t-SMS-sw', '+255712000001',
      expect.objectContaining({ policyNumber: 'POL-3E440FE9' })));
    expect(await screen.findByText('Not sent: SMS sending is switched off.')).toBeInTheDocument();
  });
});
