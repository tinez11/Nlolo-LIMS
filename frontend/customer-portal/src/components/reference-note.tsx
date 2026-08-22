/**
 * Renders one reference-code value with the platform's own placeholder caveat surfaced verbatim --
 * `refdata/V1`'s seed comment documents these five customer-readable values as placeholders pending
 * Legal/Compliance/Product/Actuarial sign-off, so nothing here is ever presented as a confirmed
 * number (spec §5).
 */
export function ReferenceNote({ label, value }: { label: string; value: string }) {
  return (
    <p className="text-sm text-muted-foreground">
      {label}: <span className="font-medium text-foreground">{value}</span>{' '}
      <span className="italic">(provisional — pending final sign-off)</span>
    </p>
  );
}
