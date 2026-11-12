/**
 * The days a sales bucket covers, within the span asked for (2026-11-12, analytics plan 3) -- so a row can open the
 * policies behind it. Dates only; no figure is worked out here.
 */
const pad = (n: number) => String(n).padStart(2, '0');
const lastDay = (y: number, m: number) => new Date(y, m, 0).getDate(); // m is 1-12

export function bucketDays(bucket: string, from: string, to: string): { from: string; to: string } {
  let start: string;
  let end: string;
  const month = /^(\d{4})-(\d{2})$/.exec(bucket);
  const quarter = /^(\d{4})-Q([1-4])$/.exec(bucket);
  if (month) {
    const y = Number(month[1]);
    const m = Number(month[2]);
    start = `${y}-${pad(m)}-01`;
    end = `${y}-${pad(m)}-${pad(lastDay(y, m))}`;
  } else if (quarter) {
    const y = Number(quarter[1]);
    const first = (Number(quarter[2]) - 1) * 3 + 1;
    start = `${y}-${pad(first)}-01`;
    end = `${y}-${pad(first + 2)}-${pad(lastDay(y, first + 2))}`;
  } else {
    start = `${bucket}-01-01`;
    end = `${bucket}-12-31`;
  }
  // ISO dates compare as strings: clip to what was asked for.
  return { from: start < from ? from : start, to: end > to ? to : end };
}

/** "2026-10" reads "Oct 2026", "2026-Q4" reads "Oct–Dec 2026" -- the server's own words for its rows. */
export function bucketWords(bucket: string): string {
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const month = /^(\d{4})-(\d{2})$/.exec(bucket);
  if (month) return `${months[Number(month[2]) - 1]} ${month[1]}`;
  const quarter = /^(\d{4})-Q([1-4])$/.exec(bucket);
  if (quarter) {
    const q = Number(quarter[2]) - 1;
    return `${months[q * 3]}–${months[q * 3 + 2]} ${quarter[1]}`;
  }
  return bucket;
}
