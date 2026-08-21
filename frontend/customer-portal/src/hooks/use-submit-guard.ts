'use client';

import { useCallback, useRef, useState } from 'react';

/**
 * Layer 1 of the hard guard from spec §6: a SYNCHRONOUS re-entrancy check.
 *
 * The ref is checked and set at the very top of the handler, before any await. A `disabled`
 * attribute driven by useState is not equivalent: React batches state updates, so two events
 * dispatched in the same tick both pass the check before either re-render lands. `isSubmitting` is
 * still returned — for the visible affordance — but it is not the correctness mechanism.
 *
 * The ref is always cleared in `finally`, including on rejection, so a validation failure the user
 * can correct does not permanently disable their form.
 */
export function useSubmitGuard<A extends unknown[]>(fn: (...args: A) => Promise<void>) {
  const inFlight = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const submit = useCallback(async (...args: A) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setIsSubmitting(true);
    try {
      await fn(...args);
    } finally {
      inFlight.current = false;
      setIsSubmitting(false);
    }
  }, [fn]);

  return { submit, isSubmitting };
}
