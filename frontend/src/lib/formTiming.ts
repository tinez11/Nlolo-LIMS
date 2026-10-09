/**
 * When every form on the console checks its fields (2026-10-09, review decision D10).
 *
 * react-hook-form's default is `onSubmit`: nothing was said about a field until the whole form
 * was sent, so a wrong age band on a long product form surfaced only at the end. `onTouched`
 * checks a field when you leave it, and `onChange` re-checks it as you correct it, so the error
 * goes away the moment it is fixed rather than at the next blur. Submit still validates all.
 *
 * Spread first into every `useForm({...})` -- a guard in designGuards.test.ts keeps it there.
 */
export const VALIDATE_ON_TOUCH = { mode: 'onTouched', reValidateMode: 'onChange' } as const;
