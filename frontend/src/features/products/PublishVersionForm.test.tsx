import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { PublishVersionForm } from './PublishVersionForm';

/**
 * The double-count guard, as the user actually meets it.
 *
 * ## What this is defending
 *
 * Age and smoker status are KEYS of the base rate table. Once rates are supplied,
 * a rating-table multiplier on either is applied a second time on top of the rate
 * it already selected, and the backend refuses the publish with a 422.
 *
 * The schema refuses it too -- but only from its object-level refinement, and zod
 * never runs that once any field on the row has failed. On this row a field has
 * always failed: the AGE row shipped pre-filled and empty, so the row-level rule
 * "an AGE factor needs a from and to age" fires first and the guard's message is
 * dropped. What reached the screen was the opposite instruction -- fill the ages
 * in -- on a row whose only correct fate is deletion.
 *
 * So the warning is derived in the form from the live values, and these tests
 * hold that: it appears while typing rather than on submit, it displaces the
 * field-level complaints instead of queueing behind them, and it goes away with
 * the rates. The last test holds the same flip on the panel's own instructions,
 * which named AGE as required no matter what.
 */

function renderForm() {
  return render(
    <PublishVersionForm productId="p-1" category="ENDOWMENT" onPublished={() => {}} />,
  );
}

/** The first rate cell of the first band -- pricing anything at all flips the mode. */
async function priceOneCell(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Add age band' }));
  await user.type(screen.getByLabelText('Base rate 1 non-smoker rate'), '0.62');
}

const DOUBLE_COUNT = /Age is a key of the base rate table below/;

describe('PublishVersionForm base rate double-count guard', () => {
  it('says nothing while the version is unpriced', () => {
    renderForm();
    expect(screen.queryByText(DOUBLE_COUNT)).not.toBeInTheDocument();
    expect(
      screen.getByText('Rating table — must cover at least AGE and SUM_ASSURED_BAND'),
    ).toBeInTheDocument();
  });

  it('warns on the AGE row as soon as a rate is typed, without a submit', async () => {
    const user = userEvent.setup();
    renderForm();
    // The AGE row is row 1 of the two the form starts with.
    expect(screen.getByLabelText('Rating factor 1 type')).toHaveValue('AGE');

    await priceOneCell(user);

    expect(screen.getByText(DOUBLE_COUNT)).toBeInTheDocument();
    expect(screen.getByText(DOUBLE_COUNT).textContent).toContain('Remove this row');
    // The factor type is what is wrong, so that is what is marked -- not the age
    // bounds, which would read as "fill these in".
    expect(screen.getByLabelText('Rating factor 1 type')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Rating factor 1 from age')).not.toHaveAttribute('aria-invalid');
  });

  it('displaces the field-level message that told the user to fill the ages in', async () => {
    const user = userEvent.setup();
    renderForm();

    // Submit unpriced first, so the row-level "needs a from and to age" is live in
    // `errors` -- this is the exact state that used to swallow the guard.
    await user.click(screen.getByRole('button', { name: 'Publish version' }));
    expect(await screen.findByText(/needs a from and to age/)).toBeInTheDocument();

    await priceOneCell(user);

    expect(screen.getByText(DOUBLE_COUNT)).toBeInTheDocument();
    expect(screen.queryByText(/needs a from and to age/)).not.toBeInTheDocument();
  });

  it('drops the warning again when the rate is cleared', async () => {
    const user = userEvent.setup();
    renderForm();
    await priceOneCell(user);
    expect(screen.getByText(DOUBLE_COUNT)).toBeInTheDocument();

    await user.clear(screen.getByLabelText('Base rate 1 non-smoker rate'));

    expect(screen.queryByText(DOUBLE_COUNT)).not.toBeInTheDocument();
  });

  it('stops requiring AGE in its own instructions once rates are supplied', async () => {
    const user = userEvent.setup();
    renderForm();
    await priceOneCell(user);

    expect(
      screen.getByText('Rating table — must cover at least SUM_ASSURED_BAND'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('Rating table — must cover at least AGE and SUM_ASSURED_BAND'),
    ).not.toBeInTheDocument();
  });
});

/**
 * The benefit schedule, which is now the thing a claim is valued against.
 *
 * An array-level `.min(1)` raises its error at the array rather than on any field, which is
 * exactly the shape that produced the silent refusal this file was written about: the rule
 * refuses the submit and nothing on screen says so. So the message is rendered above the
 * rows, and asserted here rather than assumed.
 */
describe('PublishVersionForm benefit schedule', () => {
  it('starts with one benefit row rather than an empty panel', () => {
    renderForm();
    expect(screen.getByLabelText('Benefit 1 type')).toHaveValue('DEATH');
    expect(screen.getByLabelText('Benefit 1 calculation method')).toHaveValue('SUM_ASSURED');
  });

  it('shows the amount input the selected method needs, and only that one', async () => {
    const user = userEvent.setup();
    renderForm();

    // A full sum assured benefit takes no amount at all -- the shape rule refuses one.
    expect(screen.queryByLabelText('Benefit 1 percentage')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Benefit 1 flat amount')).not.toBeInTheDocument();

    await user.selectOptions(
      screen.getByLabelText('Benefit 1 calculation method'),
      'PERCENTAGE_OF_SUM_ASSURED',
    );
    expect(screen.getByLabelText('Benefit 1 percentage')).toBeInTheDocument();
    expect(screen.queryByLabelText('Benefit 1 flat amount')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Benefit 1 calculation method'), 'FLAT_AMOUNT');
    expect(screen.getByLabelText('Benefit 1 flat amount')).toBeInTheDocument();
    expect(screen.queryByLabelText('Benefit 1 percentage')).not.toBeInTheDocument();
  });

  it('says why the submit was refused when every benefit has been removed', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: 'Remove benefit' }));
    expect(screen.queryByLabelText('Benefit 1 type')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Publish version' }));

    expect(await screen.findByText('A product must cover at least one benefit')).toBeInTheDocument();
  });

  it('says a percentage benefit needs its percentage', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.selectOptions(
      screen.getByLabelText('Benefit 1 calculation method'),
      'PERCENTAGE_OF_SUM_ASSURED',
    );
    await user.click(screen.getByRole('button', { name: 'Publish version' }));

    expect(await screen.findByText('A percentage benefit needs a percentage')).toBeInTheDocument();
  });
});

describe('PublishVersionForm cash value', () => {
  it('offers a cash-value table on a savings product', () => {
    renderForm();   // ENDOWMENT
    expect(screen.getByRole('button', { name: 'Add policy year' })).toBeInTheDocument();
  });

  it('offers none on pure protection, which the server would refuse', () => {
    render(<PublishVersionForm productId="p-2" category="TERM_LIFE" onPublished={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Add policy year' })).not.toBeInTheDocument();
  });

  it('refuses a table without its actuarial sign-off, in the server wording', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole('button', { name: 'Add policy year' }));
    await user.type(screen.getByLabelText('Cash value 1 policy year'), '2');
    await user.type(screen.getByLabelText('Cash value 1 value per 1,000'), '200');
    await user.click(screen.getByRole('button', { name: 'Publish version' }));
    expect(await screen.findByText(
      'A cash-value table needs the actuarial basis reference and date it was signed off under',
    )).toBeInTheDocument();
  });
});

describe('PublishVersionForm payout schedule', () => {
  it('offers payout rows on a savings product and none on pure protection', () => {
    const { unmount } = renderForm(); // ENDOWMENT
    expect(screen.getByRole('button', { name: 'Add a payout' })).toBeInTheDocument();
    unmount();

    // TERM_LIFE still gets free-look days -- it has a window even though it pays nothing before
    // death -- but no schedule, which the server would refuse.
    render(<PublishVersionForm productId="p-2" category="TERM_LIFE" onPublished={() => {}} />);
    expect(screen.getByLabelText('Free-look days')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add a payout' })).not.toBeInTheDocument();
  });

  it('refuses an endowment with no maturity row, in the server wording', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole('button', { name: 'Publish version' }));
    expect(
      await screen.findByText('An ENDOWMENT product must carry exactly one MATURITY row'),
    ).toBeInTheDocument();
  });

  it('asks how survival benefits affect the death benefit only once a survival row exists', async () => {
    const user = userEvent.setup();
    renderForm();
    expect(
      screen.queryByLabelText('Survival benefits paid come off the death benefit'),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Add a payout' }));
    await user.selectOptions(screen.getByLabelText('Payout 1 kind'), 'SURVIVAL');

    // Neither this nor the proof-of-life interval has a sensible default, which is why the server
    // refuses to guess one -- so each appears the moment a row makes it necessary.
    expect(
      await screen.findByLabelText('Survival benefits paid come off the death benefit'),
    ).toBeInTheDocument();
  });
});

/*
  Product step 3: a savings version is valued by a cash-value table OR by an account, never both --
  the server refuses the pair -- so choosing the account hides the table rather than leaving two
  value models on one form for the author to reconcile.
*/
describe('PublishVersionForm value basis', () => {
  it('offers the cash-value table on a scale version, the default', () => {
    renderForm();
    expect(screen.getByText('Cash value (optional)')).toBeInTheDocument();
    expect(screen.queryByLabelText('Guaranteed interest rate (% a year)')).not.toBeInTheDocument();
  });

  it('choosing the savings account reveals its terms and hides the cash-value table', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.selectOptions(screen.getByLabelText('Value basis'), 'ACCOUNT');
    expect(screen.getByLabelText('Guaranteed interest rate (% a year)')).toBeInTheDocument();
    expect(screen.getByLabelText('Minimum balance after a withdrawal')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Add a charge row/ })).toBeInTheDocument();
    expect(screen.queryByText('Cash value (optional)')).not.toBeInTheDocument();
  });

  it('is not offered on a category that cannot carry an account', () => {
    render(<PublishVersionForm productId="p-1" category="TERM_LIFE" onPublished={() => {}} />);
    expect(screen.queryByLabelText('Value basis')).not.toBeInTheDocument();
  });
});
