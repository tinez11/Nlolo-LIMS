import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { ProductCategory } from '@/api/types';
import { PublishVersionForm } from './PublishVersionForm';

// The Charges step ticks from the Account charges list (2026-10-22, V34).
vi.mock('@/api/accountCharges', async (original) => ({
  ...(await original<typeof import('@/api/accountCharges')>()),
  listAccountCharges: vi.fn(async () => [{
    chargeId: 'c-1', name: 'Premium allocation', description: null, when: 'PREMIUM', amountType: 'PERCENT', amount: 2,
    currency: 'TZS', fromPolicyYear: 1, toPolicyYear: 3, active: true, createdBy: 'finance', createdAt: '2026-10-22T08:00:00Z',
  }]),
}));

/**
 * The version form as steps (2026-10-22). Each step is reached the way a person reaches it, from the step list; a
 * publish is attempted from Review, which lists what is wrong with a way back to it.
 */

type User = ReturnType<typeof userEvent.setup>;

function renderForm(category: ProductCategory = 'ENDOWMENT', portfolioCode?: string) {
  return render(
    <PublishVersionForm productId="p-1" category={category} portfolioCode={portfolioCode} onPublished={() => {}} />,
  );
}

async function step(user: User, name: string) {
  await user.click(within(screen.getByRole('list', { name: 'Steps' })).getByRole('button', { name: new RegExp(`^${name}`) }));
}

async function publish(user: User) {
  await step(user, 'Review');
  await user.click(screen.getByRole('button', { name: 'Publish version' }));
}

/** The first rate cell of a new band -- pricing anything at all flips the version to priced. */
async function priceOneCell(user: User) {
  await user.click(screen.getByRole('button', { name: 'Add band' }));
  await user.type(screen.getByLabelText('Band 1, female non-smoker rate'), '0.62');
}

const DOUBLE_COUNT = /Age is a key of the base rate table below/;

describe('PublishVersionForm steps', () => {
  it('asks what a savings product is, starting where its portfolio says', () => {
    renderForm('ENDOWMENT', 'DEP');
    expect(screen.getByRole('radio', { name: /Fixed deposit/ })).toBeChecked();
  });

  it('asks nothing of the kind on a category that can only be one', () => {
    renderForm('TERM_LIFE');
    expect(screen.queryByRole('radiogroup', { name: 'Product type' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Steps' })).queryByRole('button', { name: /^Product type/ })).not.toBeInTheDocument();
  });

  it('asks a fixed deposit for no rate bands, loadings or cash value', async () => {
    const user = userEvent.setup();
    renderForm('ENDOWMENT', 'DEP');
    await step(user, 'Rates');
    expect(screen.getByRole('button', { name: 'Add a term' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add band' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Monthly loading %')).not.toBeInTheDocument();
    await step(user, 'What it pays');
    expect(screen.queryByRole('button', { name: 'Add policy year' })).not.toBeInTheDocument();
    expect(screen.queryByText('With profits (optional)')).not.toBeInTheDocument();
  });

  it('offers charges only to a product that keeps an account', async () => {
    const user = userEvent.setup();
    renderForm('ENDOWMENT');
    const steps = () => within(screen.getByRole('list', { name: 'Steps' }));
    expect(steps().queryByRole('button', { name: /^Charges/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: /Savings account/ }));
    expect(steps().getByRole('button', { name: /^Charges/ })).toBeInTheDocument();
  });

  it('lists what is wrong on Review, with the way back to it', async () => {
    const user = userEvent.setup();
    renderForm('TERM_LIFE');
    await publish(user);
    expect(await screen.findByText('Effective date is required')).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: 'Go to Basics' })[0]!);
    expect(screen.getByLabelText('Effective date')).toBeInTheDocument();
  });
});

describe('PublishVersionForm base rates and adjustments', () => {
  it('starts with the neutral factors filled in, so nothing has to be typed to publish them', async () => {
    const user = userEvent.setup();
    renderForm('TERM_LIFE');
    await step(user, 'Pricing');
    expect(screen.getByLabelText('Rating factor 1 type')).toHaveValue('AGE');
    expect(screen.getByLabelText('Rating factor 1 from age')).toHaveValue(0);
    expect(screen.getByLabelText('Rating factor 2 type')).toHaveValue('SUM_ASSURED_BAND');
    expect(screen.getByLabelText('Rating factor 2 band')).toHaveValue('All amounts');
  });

  it('drops the untouched age factor once a rate is typed, and brings it back when the rate is cleared', async () => {
    const user = userEvent.setup();
    renderForm('TERM_LIFE');
    await step(user, 'Pricing');
    await priceOneCell(user);
    expect(screen.getByLabelText('Rating factor 1 type')).toHaveValue('SUM_ASSURED_BAND');
    expect(screen.queryByText(DOUBLE_COUNT)).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText('Band 1, female non-smoker rate'));
    expect(screen.getByLabelText('Rating factor 1 type')).toHaveValue('AGE');
  });

  it('warns on an age factor the author changed, as soon as a rate is typed', async () => {
    const user = userEvent.setup();
    renderForm('TERM_LIFE');
    await step(user, 'Pricing');
    await user.type(screen.getByLabelText('Rating factor 1 band'), 'Adults');
    await priceOneCell(user);

    expect(screen.getByText(DOUBLE_COUNT)).toBeInTheDocument();
    expect(screen.getByText(DOUBLE_COUNT).textContent).toContain('Remove this row');
    // The factor type is what is wrong, so that is what is marked -- not the age bounds.
    expect(screen.getByLabelText('Rating factor 1 type')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Rating factor 1 from age')).not.toHaveAttribute('aria-invalid');
  });

  it('displaces the field-level message that told the author to fill the ages in', async () => {
    const user = userEvent.setup();
    renderForm('TERM_LIFE');
    await step(user, 'Pricing');
    await user.clear(screen.getByLabelText('Rating factor 1 from age'));
    await publish(user);
    expect(await screen.findByText(/needs a from and to age/)).toBeInTheDocument();

    await step(user, 'Pricing');
    await priceOneCell(user);
    expect(screen.getByText(DOUBLE_COUNT)).toBeInTheDocument();
    expect(screen.queryByText(/needs a from and to age/)).not.toBeInTheDocument();
  });
});

/** The benefit schedule, the thing a claim is valued against. */
describe('PublishVersionForm benefit schedule', () => {
  it('starts with one benefit row rather than an empty panel', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    expect(screen.getByLabelText('Benefit 1 type')).toHaveValue('DEATH');
    expect(screen.getByLabelText('Benefit 1 calculation method')).toHaveValue('SUM_ASSURED');
  });

  it('shows the amount input the selected method needs, and only that one', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    expect(screen.queryByLabelText('Benefit 1 percentage')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Benefit 1 flat amount')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Benefit 1 calculation method'), 'PERCENTAGE_OF_SUM_ASSURED');
    expect(screen.getByLabelText('Benefit 1 percentage')).toBeInTheDocument();
    expect(screen.queryByLabelText('Benefit 1 flat amount')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Benefit 1 calculation method'), 'FLAT_AMOUNT');
    expect(screen.getByLabelText('Benefit 1 flat amount')).toBeInTheDocument();
    expect(screen.queryByLabelText('Benefit 1 percentage')).not.toBeInTheDocument();
  });

  it('says why the publish was refused when every benefit has been removed', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    await user.click(screen.getByRole('button', { name: 'Remove benefit' }));
    expect(screen.queryByLabelText('Benefit 1 type')).not.toBeInTheDocument();

    await publish(user);
    expect(await screen.findByText('A product must cover at least one benefit')).toBeInTheDocument();
  });

  it('says a percentage benefit needs its percentage', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    await user.selectOptions(screen.getByLabelText('Benefit 1 calculation method'), 'PERCENTAGE_OF_SUM_ASSURED');
    await publish(user);
    expect(await screen.findByText('A percentage benefit needs a percentage')).toBeInTheDocument();
  });
});

describe('PublishVersionForm cash value', () => {
  it('offers a cash-value table on a cash-value plan', async () => {
    const user = userEvent.setup();
    renderForm(); // ENDOWMENT, cash-value by default
    await step(user, 'What it pays');
    expect(screen.getByRole('button', { name: 'Add policy year' })).toBeInTheDocument();
  });

  it('offers none on pure protection, which the server would refuse', async () => {
    const user = userEvent.setup();
    renderForm('TERM_LIFE');
    await step(user, 'What it pays');
    expect(screen.queryByRole('button', { name: 'Add policy year' })).not.toBeInTheDocument();
  });

  it('refuses a table without its actuarial sign-off, in the server wording', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    await user.click(screen.getByRole('button', { name: 'Add policy year' }));
    await user.type(screen.getByLabelText('Cash value 1 policy year'), '2');
    await user.type(screen.getByLabelText('Cash value 1 value per 1,000'), '200');
    await publish(user);
    expect(
      await screen.findByText('A cash-value table needs the actuarial basis reference and date it was signed off under'),
    ).toBeInTheDocument();
  });
});

describe('PublishVersionForm payout schedule', () => {
  it('offers payout rows on an endowment and none on pure protection', async () => {
    const user = userEvent.setup();
    const { unmount } = renderForm();
    await step(user, 'What it pays');
    expect(screen.getByRole('button', { name: 'Add a payout' })).toBeInTheDocument();
    unmount();

    // TERM_LIFE still has a free-look window even though it pays nothing before death.
    renderForm('TERM_LIFE');
    await step(user, 'What it pays');
    expect(screen.getByLabelText('Free-look days')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add a payout' })).not.toBeInTheDocument();
  });

  it('refuses an endowment with no maturity row, in the server wording', async () => {
    const user = userEvent.setup();
    renderForm();
    await publish(user);
    expect(await screen.findByText('An ENDOWMENT product must carry exactly one MATURITY row')).toBeInTheDocument();
  });

  it('asks how survival benefits affect the death benefit only once a survival row exists', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    expect(screen.queryByLabelText('Survival benefits paid come off the death benefit')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Add a payout' }));
    await user.selectOptions(screen.getByLabelText('Payout 1 kind'), 'SURVIVAL');
    expect(await screen.findByLabelText('Survival benefits paid come off the death benefit')).toBeInTheDocument();
  });
});

/*
  A savings version is valued by a cash-value table OR by an account, never both -- the server refuses the pair -- so
  choosing the account hides the table rather than leaving two value models on one form.
*/
describe('PublishVersionForm product type', () => {
  it('offers the cash-value table on a cash-value plan, the default', async () => {
    const user = userEvent.setup();
    renderForm();
    await step(user, 'What it pays');
    expect(screen.getByText('Cash value (optional)')).toBeInTheDocument();
  });

  it('choosing the savings account reveals its terms and hides the cash-value table', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole('radio', { name: /Savings account/ }));
    await step(user, 'Rates');
    expect(screen.getByLabelText('Guaranteed interest rate (% a year)')).toBeInTheDocument();
    expect(screen.getByLabelText('Minimum balance after a withdrawal')).toBeInTheDocument();
    await step(user, 'Charges');
    // Added from Account charges, not typed: only what is picked is listed, each with when, how much and which years.
    expect(await screen.findByText('No charges: the account charges nothing.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Add charge/ }));
    expect(screen.getByRole('button', { name: /New charge/ })).toBeInTheDocument();
    await user.click(within(screen.getByRole('list', { name: 'Charges to add' })).getByRole('button', { name: /Premium allocation/ }));
    const picked = screen.getByRole('list', { name: 'Charges picked' });
    expect(picked).toHaveTextContent('On each premium or top-up · 2% of each premium · Years 1–3');
    await user.click(within(picked).getByRole('button', { name: 'Remove Premium allocation' }));
    expect(screen.queryByRole('list', { name: 'Charges picked' })).not.toBeInTheDocument();
    await step(user, 'What it pays');
    expect(screen.queryByText('Cash value (optional)')).not.toBeInTheDocument();
  });
});
