const COUNT = new Intl.NumberFormat('en-GB');

/**
 * A count for display, grouped -- "1,234".
 *
 * <b>Null and undefined stay null.</b> An absent count is "Not available" on the card, never "0": the platform
 * saying nothing and the platform saying zero are different answers, and only one of them is a fact about the book.
 *
 * Beside `formatMoney` and `formatDate` rather than in the card that uses it, so a component file exports only
 * components (the lint rule every feature file here obeys).
 */
export function countValue(n: number | null | undefined): string | null {
  return n === null || n === undefined ? null : COUNT.format(n);
}
