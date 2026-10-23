import { useEffect, useState } from 'react';

/** One alternative to price: what it is called, and how the server prices it. */
export interface Alternative {
  key: string;
  /** What differs, e.g. "TZS 150,000 a month". */
  label: string;
  /** Is this the one already quoted above. */
  current: boolean;
  /** Asks the server; resolves to the figure to show and what it means. */
  price: () => Promise<{ value: string; note: string }>;
}

export type Priced = { key: string; label: string; current: boolean } & (
  | { state: 'loading' }
  | { state: 'priced'; value: string; note: string }
  | { state: 'refused' }
);

/**
 * Prices a few alternatives side by side (2026-10-23, "Compare options"). Every figure is a quote the server made for
 * that alternative -- the page never scales the quoted one -- so each card is exactly what the customer would be
 * offered if they chose it. Re-asked whenever the set of alternatives changes; an answer for an older set is dropped.
 */
export function useAlternatives(alternatives: Alternative[]): Priced[] {
  const signature = alternatives.map((a) => a.key).join('|');
  const [answers, setAnswers] = useState<{ signature: string; byKey: Record<string, Priced> } | null>(null);

  useEffect(() => {
    if (!signature) return undefined;
    let live = true;
    const byKey: Record<string, Priced> = {};
    void Promise.all(alternatives.map((a) =>
      a.price().then(
        (r) => { byKey[a.key] = { key: a.key, label: a.label, current: a.current, state: 'priced', ...r }; },
        () => { byKey[a.key] = { key: a.key, label: a.label, current: a.current, state: 'refused' }; },
      ),
    )).then(() => {
      if (live) setAnswers({ signature, byKey });
    });
    return () => { live = false; };
    // The signature is the question; the closures in `alternatives` change identity every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);

  return alternatives.map((a) =>
    answers?.signature === signature && answers.byKey[a.key]
      ? answers.byKey[a.key]!
      : { key: a.key, label: a.label, current: a.current, state: 'loading' as const },
  );
}
