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
  callerSubject?: string | null;
} = {}) {
  const onDecide = over.onDecide ?? vi.fn();
  render(
    <DecisionPanel
      view={decidedCase(over.view)}
      deciding={over.deciding ?? idle<UnderwritingCaseView>()}
      canDecide={over.canDecide ?? true}
      isSenior={over.isSenior ?? false}
      callerSubject={over.callerSubject ?? 'decider-sub'}
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

    // The submit arms the confirmation; nothing is decided until the second click.
    expect(onDecide).not.toHaveBeenCalled();
    expect(screen.getByText('Record this acceptance?')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Record the acceptance' }));

    expect(onDecide).toHaveBeenCalledWith(
      expect.objectContaining({ outcome: 'ACCEPT', reason: 'Agrees with the recommendation' }),
    );
  });

  /**
   * The decision was the one comparably consequential act with no confirmation: a decided case
   * is closed to further evidence unless it was POSTPONED, and on an acceptance it issues the
   * policy. The confirmation has to state which of those is about to happen.
   */
  it('states the consequence and the absence of a way back before deciding', async () => {
    const user = userEvent.setup();
    const { onDecide } = renderPanel({ isSenior: true });

    await user.selectOptions(screen.getByLabelText('Decision'), 'DECLINED');
    await user.type(screen.getByLabelText('Reason'), 'Adverse history disclosed off-system');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));

    expect(screen.getByText('Decline this risk?')).toBeInTheDocument();
    expect(screen.getByText(/No policy is issued/)).toBeInTheDocument();
    expect(screen.getByText(/the case closes to further evidence/)).toBeInTheDocument();
    expect(onDecide).not.toHaveBeenCalled();
  });

  it('lets the underwriter back out of the confirmation without deciding', async () => {
    const user = userEvent.setup();
    const { onDecide } = renderPanel();

    await user.type(screen.getByLabelText('Reason'), 'Agrees with the recommendation');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onDecide).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Record decision' })).toBeEnabled();
  });

  /**
   * A postponement is the one outcome that can be decided again, so saying "nothing can undo
   * this" there would be the overstatement `ConfirmAct` exists to prevent.
   */
  it('tells the truth about a postponement being recoverable', async () => {
    const user = userEvent.setup();
    // Senior: postponing departs from the ACCEPT recommendation, so a junior is blocked
    // before any confirmation is reached.
    renderPanel({ isSenior: true });

    await user.selectOptions(screen.getByLabelText('Decision'), 'POSTPONED');
    await user.type(screen.getByLabelText('Reason'), 'Awaiting the medical report');
    await user.click(screen.getByRole('button', { name: 'Record decision' }));

    expect(screen.getByText(/can be decided again once new evidence arrives/)).toBeInTheDocument();
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
    await user.click(screen.getByRole('button', { name: 'Decline the risk' }));

    expect(onDecide).toHaveBeenCalledWith(expect.objectContaining({ outcome: 'DECLINED' }));
  });

  /**
   * This test used to assert the opposite, on a premise that was simply wrong: "a case with no
   * recommendation has nothing to disagree with, so a junior may decide it freely." The server
   * does not let ANYBODY decide such a case -- `UnderwritingApiImpl.decide` refuses a case with
   * no assessment, and no recommendation means no assessment, because the engine runs on every
   * assessment and all four of its paths return an outcome.
   *
   * The panel said the same thing in as many words ("You can still decide"), so the screen
   * invited an underwriter to fill the form and then handed them a raw validation error.
   */
  it('withholds the form until an assessment exists, rather than inviting a refusal', () => {
    renderPanel({ view: { recommendationOutcome: null } });

    expect(screen.queryByLabelText('Decision')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument();
    expect(screen.getByText(/Record an assessment first/)).toBeInTheDocument();
  });

  it('no longer claims an unassessed case can be decided', () => {
    renderPanel({ view: { recommendationOutcome: null, recommendationReason: null } });

    expect(screen.getByText(/No recommendation yet/)).toBeInTheDocument();
    expect(screen.queryByText(/You can\s+still decide/)).not.toBeInTheDocument();
  });
});

describe('separation of duties', () => {
  it('withholds the form from whoever assessed the case, and says who must decide', () => {
    renderPanel({ view: { openedBy: 'agent-sub', assessedBy: ['me-sub'] }, callerSubject: 'me-sub' });
    expect(screen.getByText(/recorded an assessment on this case/)).toBeInTheDocument();
    expect(screen.getByText(/another underwriter must decide it/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument();
    // The advice stays visible: they may still read what they are handing over.
    expect(screen.getByText(/The rules engine recommends/)).toBeInTheDocument();
  });

  it('withholds it from whoever opened the case, senior or not', () => {
    renderPanel({ view: { openedBy: 'me-sub', assessedBy: [] }, callerSubject: 'me-sub', isSenior: true });
    expect(screen.getByText(/You opened this case/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument();
  });

  it('offers it to anyone else', () => {
    renderPanel({ view: { openedBy: 'agent-sub', assessedBy: ['assessor-sub'] }, callerSubject: 'me-sub' });
    expect(screen.getByRole('button', { name: 'Record decision' })).toBeEnabled();
  });
});

describe("a scheme member's evidence case", () => {
  it('offers no loading, since one member of a scheme has no premium of their own', () => {
    renderPanel({ view: { evidenceForPolicyNumber: 'GRP-1', evidenceForMemberId: 'm-1' } });
    expect(screen.queryByRole('option', { name: 'Accept with a loading' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Accept' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Decline' })).toBeInTheDocument();
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
        callerSubject="decider-sub"
        onDecide={() => {}}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
