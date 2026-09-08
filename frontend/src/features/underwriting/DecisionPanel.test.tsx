import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { UnderwritingCaseView } from '@/api/types';
import { idle, type Resource } from '@/store/createResourceSlice';
import { DecisionPanel } from './DecisionPanel';

/**
 * The senior-underwriter gate, as an underwriter actually meets it.
 *
 * The rule is not expressible as a role check on the panel: a junior may record the decision
 * the engine recommended and may not record any other, so whether a given submission is
 * permitted depends on the outcome currently selected. These tests hold that it is decided
 * live, that the blocked option stays visible rather than being hidden, and that a case with
 * no recommendation never counts as a disagreement.
 */

const decidedCase = (over: Partial<UnderwritingCaseView> = {}): UnderwritingCaseView =>
  ({
    caseId: 'c-1',
    status: 'IN_REVIEW',
    recommendationOutcome: 'ACCEPT',
    recommendationReason: 'Standard risk profile',
    ...over,
  }) as UnderwritingCaseView;

function renderPanel(over: {
  view?: Partial<UnderwritingCaseView>;
  isSenior?: boolean;
  canDecide?: boolean;
  deciding?: Resource<UnderwritingCaseView>;
  onDecide?: (r: unknown) => void;
} = {}) {
  const onDecide = over.onDecide ?? vi.fn();
  render(
    <DecisionPanel
      view={decidedCase(over.view)}
      deciding={over.deciding ?? idle<UnderwritingCaseView>()}
      canDecide={over.canDecide ?? true}
      isSenior={over.isSenior ?? false}
      onDecide={onDecide}
    />,
  );
  return { onDecide };
}

describe('the engine recommendation', () => {
  it('is shown as advice, and says so', () => {
    renderPanel();
    expect(screen.getByText(/The rules engine recommends/)).toBeInTheDocument();
    expect(screen.getByText('Standard risk profile')).toBeInTheDocument();
    expect(screen.getByText(/A recommendation, not a decision/)).toBeInTheDocument();
  });

  it('says plainly when there is none, rather than rendering an empty box', () => {
    renderPanel({ view: { recommendationOutcome: null, recommendationReason: null } });
    expect(screen.getByText(/No recommendation yet/)).toBeInTheDocument();
  });
});

describe('the senior underwriter gate', () => {
  it('lets a junior record the recommended outcome', async () => {
    const user = userEvent.setup();
    const { onDecide } = renderPanel();

    await user.type(screen.getByLabelText('Reason'), 'Agrees with the recommendation');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));

    expect(onDecide).toHaveBeenCalledWith(
      expect.objectContaining({ outcome: 'ACCEPT', reason: 'Agrees with the recommendation' }),
    );
  });

  it('blocks a junior departing from it, and says who can', async () => {
    const user = userEvent.setup();
    const { onDecide } = renderPanel();

    await user.selectOptions(screen.getByLabelText('Decision'), 'DECLINED');

    expect(screen.getByText(/a senior underwriter has to record it/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record decision' })).toBeDisabled();
    expect(onDecide).not.toHaveBeenCalled();
  });

  /**
   * Disabled, not hidden. A junior who cannot see the option cannot tell a decision they may
   * not make from one the form does not support, and "fetch a senior" is only an obvious next
   * step if the restriction is visible.
   */
  it('keeps the blocked outcome selectable so the restriction is legible', () => {
    renderPanel();
    expect(screen.getByRole('option', { name: 'Decline' })).toBeInTheDocument();
  });

  it('lets a senior depart from it, and warns that it is recorded as an override', async () => {
    const user = userEvent.setup();
    const { onDecide } = renderPanel({ isSenior: true });

    await user.selectOptions(screen.getByLabelText('Decision'), 'DECLINED');
    expect(screen.getByText(/recorded as an override, against your name/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Reason'), 'Adverse history disclosed off-system');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));

    expect(onDecide).toHaveBeenCalledWith(expect.objectContaining({ outcome: 'DECLINED' }));
  });

  /**
   * Matches the server: a case with no recommendation has nothing to disagree with, so a
   * junior may decide it freely.
   */
  it('does not treat a case with no recommendation as an override', async () => {
    const user = userEvent.setup();
    renderPanel({ view: { recommendationOutcome: null } });

    await user.selectOptions(screen.getByLabelText('Decision'), 'DECLINED');

    expect(screen.queryByText(/a senior underwriter has to record it/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record decision' })).toBeEnabled();
  });
});

describe('the loading field', () => {
  it('appears only for an accepted-with-loading decision', async () => {
    const user = userEvent.setup();
    renderPanel({ isSenior: true });

    expect(screen.queryByLabelText('Loading (%)')).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Decision'), 'LOADED');
    expect(screen.getByLabelText('Loading (%)')).toBeInTheDocument();
  });
});

describe('a server refusal', () => {
  /**
   * The client gate and the server's can legitimately disagree -- whether a decision is an
   * override depends on the recommendation at the moment the server reads it, and a colleague
   * submitting an assessment meanwhile moves it. So the 403 has to reach the screen.
   */
  it('is shown even when the panel believed the decision was allowed', () => {
    renderPanel({
      deciding: {
        status: 'error',
        data: null,
        error: {
          kind: 'forbidden',
          title: 'Forbidden',
          detail: 'Case c-1 was recommended ACCEPT and a decision of DECLINED departs from it',
          traceId: 'trace-123',
        },
      } as unknown as Resource<UnderwritingCaseView>,
    });

    expect(screen.getByRole('alert')).toHaveTextContent(/departs from it/);
    expect(screen.getByText(/trace-123/)).toBeInTheDocument();
  });
});

describe('visibility', () => {
  it('renders nothing for a staff user without the underwriter role', () => {
    const { container } = render(
      <DecisionPanel
        view={decidedCase()}
        deciding={idle<UnderwritingCaseView>()}
        canDecide={false}
        isSenior={false}
        onDecide={() => {}}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
