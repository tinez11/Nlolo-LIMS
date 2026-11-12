/**
 * The only return here is a placeholder (2026-11-12, finance finding 179; the user's D16: TIRA's templates not yet
 * available). Its definition was written before TIRA's return catalogue was known, and its figures are not reconciled
 * to the ledger -- so every page that shows one says so, where it could otherwise be filed as real.
 */
export function PlaceholderReturnNotice() {
  return (
    <div role="note" className="rounded-md bg-status-warning-bg px-3 py-2 text-sm text-status-warning-fg">
      <p className="font-medium">Placeholder — not for filing</p>
      <p>
        This return was made before TIRA&apos;s return templates were available. Its lines and figures are not TIRA&apos;s and
        are not checked against the ledger. Do not file it.
      </p>
    </div>
  );
}
