import { zodResolver } from '@hookform/resolvers/zod';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useForm } from 'react-hook-form';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { VALIDATE_ON_TOUCH } from './formTiming';

const schema = z.object({ rate: z.string().regex(/^\d+$/, 'Must be a whole number') });

function RateForm() {
  const { register, formState: { errors } } = useForm<z.infer<typeof schema>>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(schema),
    defaultValues: { rate: '' },
  });
  return (
    <form>
      <label htmlFor="rate">Rate</label>
      <input id="rate" {...register('rate')} />
      {errors.rate && <p role="alert">{errors.rate.message}</p>}
      <button type="button">Elsewhere</button>
    </form>
  );
}

describe('VALIDATE_ON_TOUCH', () => {
  it('says a field is wrong when it is left, with no submit', async () => {
    const user = userEvent.setup();
    render(<RateForm />);
    await user.type(screen.getByLabelText('Rate'), '1.5');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Must be a whole number');
  });

  it('takes the error away as soon as the field is corrected', async () => {
    const user = userEvent.setup();
    render(<RateForm />);
    const field = screen.getByLabelText('Rate');
    await user.type(field, 'x');
    await user.tab();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await user.clear(field);
    await user.type(field, '7');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
