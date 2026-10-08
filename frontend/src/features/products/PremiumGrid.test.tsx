import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { PublishVersionForm } from './PublishVersionForm';

/** The funeral premium table as a grid (2026-10-08): built from the plans and roles above it, one price per box. */
describe('PremiumGrid', () => {
  async function planA(user: ReturnType<typeof userEvent.setup>) {
    render(<PublishVersionForm productId="p-1" category="FUNERAL" onPublished={() => {}} />);
    await user.click(screen.getByRole('button', { name: 'Add plan' }));
    await user.type(screen.getByLabelText('Plan code'), 'A');
    await user.type(screen.getByLabelText('Main member benefit'), '400000');
  }

  it('builds a box for each covered role, and splits a role into age bands where its price changes', async () => {
    const user = userEvent.setup();
    await planA(user);

    // Only the main member is covered: one box, the default entry ages 18 to the highest priced age, 100.
    expect(screen.getByLabelText('A Main member ages 18–100 yearly premium')).toBeInTheDocument();
    expect(screen.queryByLabelText(/A Child ages/)).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Child benefit'), '100000');
    // The default child cover stops at 21, 25 as a student: priced 0 to 24.
    expect(screen.getByLabelText('A Child ages 0–24 yearly premium')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Main member: new band at ages'), '41, 56');
    expect(screen.getByLabelText('A Main member ages 18–40 yearly premium')).toBeInTheDocument();
    expect(screen.getByLabelText('A Main member ages 41–55 yearly premium')).toBeInTheDocument();
    expect(screen.getByLabelText('A Main member ages 56–100 yearly premium')).toBeInTheDocument();
  });

  it('fills from rows pasted from a spreadsheet', async () => {
    const user = userEvent.setup();
    await planA(user);

    await user.click(screen.getByRole('button', { name: 'Paste rows from a spreadsheet' }));
    await user.type(screen.getByLabelText('Premium rows'), 'A,MAIN_MEMBER,18,40,100000{Enter}A,MAIN_MEMBER,41,100,150000');
    await user.click(screen.getByRole('button', { name: 'Fill the grid' }));

    expect(screen.getByLabelText('Main member: new band at ages')).toHaveValue('41');
    expect(screen.getByLabelText('A Main member ages 18–40 yearly premium')).toHaveValue('100000');
    expect(screen.getByLabelText('A Main member ages 41–100 yearly premium')).toHaveValue('150000');
  });
});
