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

/**
 * Today as an ISO date, in the LOCAL zone. Deliberately not
 * `new Date().toISOString().slice(0, 10)`, which is UTC: this machine and the
 * backend run at +03:00, so between local midnight and 03:00 that spelling
 * returns yesterday. Any fixture whose date is compared against a server-side
 * "today" would then be off by one for three hours a day — the kind of failure
 * that only ever reproduces for whoever is working late.
 */
export function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}
