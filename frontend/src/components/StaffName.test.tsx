import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as accessApi from '@/api/access';
import { StaffName, __clearStaffNameCache } from './StaffName';

vi.mock('@/api/access');

const REHEMA = '1bccfb0a-7735-4ebd-8461-e2371a19ccd4';
const UNKNOWN = '9e0e7a3e-1111-4222-8333-944444444444';

describe('StaffName', () => {
  beforeEach(() => {
    __clearStaffNameCache();
    vi.mocked(accessApi.getStaffNames).mockReset();
  });

  it('names a staff member, asking once for every row that names them', async () => {
    vi.mocked(accessApi.getStaffNames).mockResolvedValue([{ subject: REHEMA, name: 'Rehema Finance' }]);
    render(
      <>
        <StaffName subject={REHEMA} />
        <StaffName subject={REHEMA} />
      </>,
    );
    expect(await screen.findAllByText('Rehema Finance')).toHaveLength(2);
    expect(accessApi.getStaffNames).toHaveBeenCalledTimes(1);
    expect(screen.getAllByText('Rehema Finance')[0]).toHaveAttribute('title', REHEMA);
  });

  it('keeps the subject where the platform knows nobody by it', async () => {
    vi.mocked(accessApi.getStaffNames).mockResolvedValue([]);
    render(<StaffName subject={UNKNOWN} />);
    expect(await screen.findByText(UNKNOWN)).toBeInTheDocument();
  });

  it('shows a value that is not a subject as it is, without asking', () => {
    render(<StaffName subject="system" />);
    expect(screen.getByText('system')).toBeInTheDocument();
    expect(accessApi.getStaffNames).not.toHaveBeenCalled();
  });

  it('shows a dash for nobody', () => {
    render(<StaffName subject={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});
