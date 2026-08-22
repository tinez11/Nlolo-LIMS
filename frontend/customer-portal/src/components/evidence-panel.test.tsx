import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EvidencePanel } from './evidence-panel';

describe('EvidencePanel', () => {
  it('lists existing evidence with download links', () => {
    render(<EvidencePanel claimId="c1" evidence={[{ documentRef: 'doc-1', fileName: 'report.pdf' }]} onUpload={vi.fn()} />);
    const link = screen.getByRole('link', { name: /report\.pdf/i });
    expect(link).toHaveAttribute('href', '/api/claims/c1/evidence/doc-1');
  });

  it('rejects a disallowed content type before uploading', async () => {
    const onUpload = vi.fn();
    render(<EvidencePanel claimId="c1" evidence={[]} onUpload={onUpload} />);

    const file = new File(['x'], 'notes.txt', { type: 'text/plain' });
    await userEvent.upload(screen.getByLabelText(/attach a file/i), file);

    expect(onUpload).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(/JPEG, PNG or PDF/i);
  });

  it('accepts an allowed content type', async () => {
    const onUpload = vi.fn().mockResolvedValue(undefined);
    render(<EvidencePanel claimId="c1" evidence={[]} onUpload={onUpload} />);

    const file = new File(['x'], 'scan.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText(/attach a file/i), file);

    expect(onUpload).toHaveBeenCalledTimes(1);
  });
});
