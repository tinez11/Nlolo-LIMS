import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { submitAssessment } from '@/api/underwriting';
import { renderScreen } from '@/test/renderScreen';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { UnderwritingAssessmentPanel } from './UnderwritingAssessmentPanel';

vi.mock('@/api/underwriting');

beforeEach(() => {
  vi.clearAllMocks();
  useUnderwritingStore.getState().resetSubmitAssessment('case-1');
});

/** 2026-11-07: the score explains itself, and Submit closes the form into a receipt with "Add another assessment". */
describe('UnderwritingAssessmentPanel', () => {
  it('says the scale and its bands, and what the typed score alone recommends', async () => {
    renderScreen(<UnderwritingAssessmentPanel caseId="case-1" isPostponed={false} />);
    expect(screen.getByText(/From 0 to 100: 0–39 accept/)).toBeInTheDocument();
    expect(screen.queryByText(/On its own, this score recommends/)).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Risk score'), '60');
    expect(screen.getByText(/On its own, this score recommends/)).toHaveTextContent('Accept with +20% premium');
  });

  it('closes into a receipt of what was recorded and what the case now recommends, then opens again', async () => {
    vi.mocked(submitAssessment).mockResolvedValue({
      caseId: 'case-1', recommendationOutcome: 'LOADED', recommendationLoadingPercent: 20,
    } as never);
    renderScreen(<UnderwritingAssessmentPanel caseId="case-1" isPostponed={false} />);

    await userEvent.type(screen.getByLabelText('Findings'), 'Raised blood pressure');
    await userEvent.type(screen.getByLabelText('Risk score'), '60');
    await userEvent.click(screen.getByRole('button', { name: 'Submit assessment' }));

    const receipt = await screen.findByRole('status');
    expect(receipt).toHaveTextContent('Assessment recorded');
    expect(receipt).toHaveTextContent('Medical');
    expect(receipt).toHaveTextContent('60');
    expect(receipt).toHaveTextContent('+20%');
    expect(screen.queryByLabelText('Findings')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Add another assessment' }));
    expect(screen.getByLabelText('Findings')).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Submit assessment' })).toBeInTheDocument();
  });
});
