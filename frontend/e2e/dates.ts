/**
 * The DatePicker field is typed as `DD/MM/YYYY` (Tanzanian order), while fixtures
 * and API assertions speak ISO. Converting at the call site keeps the ISO date
 * visible in the test — it is the value the backend stores and the value later
 * assertions compare against — instead of leaving a reader to reverse a reordered
 * literal by eye.
 */
export function dmy(iso: string): string {
  const [year, month, day] = iso.split('-');
  return `${day}/${month}/${year}`;
}
