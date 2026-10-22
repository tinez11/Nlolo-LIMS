/**
 * "Add row" for year or age bands: the next band starts the year (or age) after the last one ends. An empty table's
 * first row starts at `first` -- year 1 for policy-year bands, blank for ages, which start where the product does.
 */
export function continueFrom(fromIndex: number, toIndex: number, width: number, first = '') {
  return (last: string[] | undefined): string[] => {
    const row = Array.from({ length: width }, () => '');
    const end = last?.[toIndex];
    if (end && /^\d+$/.test(end)) row[fromIndex] = String(Number(end) + 1);
    else if (!last) row[fromIndex] = first;
    return row;
  };
}
