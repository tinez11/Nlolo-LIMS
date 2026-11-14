import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as accessApi from '@/api/access';
import type { StaffUser } from '@/api/access';
import { renderScreen as render } from '@/test/renderScreen';
import { StaffContactPanel } from './StaffContactPanel';

vi.mock('@/api/access');

const user = (over: Partial<StaffUser> = {}): StaffUser => ({
  userId: 'u-1', username: 'staff.finance', displayName: 'Rehema Finance', email: null, phone: '+255755418369',
  homeBranch: 'DSM', active: true, lastSeenAt: null, roles: [], extraPermissions: [], effectivePermissions: [],
  pendingApproval: false, textLanguage: 'en', ...over,
});

describe('StaffContactPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows where the code goes and lets another administrator change it', async () => {
    vi.mocked(accessApi.updateStaffContact).mockResolvedValue(user({ phone: '+255700000111', textLanguage: 'sw' }));
    const onSaved = vi.fn();
    const ui = userEvent.setup();
    render(<StaffContactPanel user={user()} editable onSaved={onSaved} />);

    expect(screen.getByText('+255755418369')).toBeInTheDocument();
    expect(screen.getByText('English')).toBeInTheDocument();
    await ui.click(screen.getByRole('button', { name: 'Change phone' }));
    const phone = screen.getByLabelText('Phone');
    await ui.clear(phone);
    await ui.type(phone, '+255700000111');
    await ui.selectOptions(screen.getByLabelText('Texts in'), 'sw');
    await ui.click(screen.getByRole('button', { name: 'Save' }));

    expect(accessApi.updateStaffContact).toHaveBeenCalledWith('u-1', '+255700000111', 'sw');
    expect(onSaved).toHaveBeenCalled();
  });

  it('warns when there is no phone, and offers nothing on your own login', () => {
    render(<StaffContactPanel user={user({ phone: null })} editable={false} onSaved={() => {}} />);
    expect(screen.getByText('No phone: they cannot get a sign-in code.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /phone/i })).not.toBeInTheDocument();
  });

  it('removes a phone when the field is emptied', async () => {
    vi.mocked(accessApi.updateStaffContact).mockResolvedValue(user({ phone: null }));
    const ui = userEvent.setup();
    render(<StaffContactPanel user={user()} editable onSaved={() => {}} />);
    await ui.click(screen.getByRole('button', { name: 'Change phone' }));
    await ui.clear(screen.getByLabelText('Phone'));
    await ui.click(screen.getByRole('button', { name: 'Save' }));
    expect(accessApi.updateStaffContact).toHaveBeenCalledWith('u-1', null, 'en');
  });
});
