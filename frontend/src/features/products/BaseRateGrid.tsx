import { Plus, X } from 'lucide-react';
import { useState, type ClipboardEvent } from 'react';
import { useWatch, type Control, type FieldErrors, type UseFormSetValue } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import {
  bandNotes,
  bandsFromRows,
  blankBand,
  cellOrder,
  editCell,
  generateBands,
  isBlock,
  pasteBlock,
  rowsFromBands,
  type Band,
} from './baseRateBands';
import type { PublishVersionFormInput } from './publishVersionSchema';

/**
 * The base rate table as a grid (2026-10-22): one line per age band, the rates across it.
 *
 * It replaced a list of rows that took two lines of eight boxes per band -- a ten-band table was some 160 boxes, typed
 * one by one, wrapping onto second lines a reader took for separate rows. Here a band is one line; bands can be laid
 * out in one go, a block can be pasted from a spreadsheet into any cell, and a gap or overlap is named on the band it
 * concerns while the table is being typed rather than refused at publish.
 */
export function BaseRateGrid({
  control,
  setValue,
  errors,
}: {
  control: Control<PublishVersionFormInput>;
  setValue: UseFormSetValue<PublishVersionFormInput>;
  errors: FieldErrors<PublishVersionFormInput>;
}) {
  const rows = useWatch({ control, name: 'baseRates' }) ?? [];
  const bands = bandsFromRows(rows);

  // Two optional dimensions, off until used. On when an existing table already uses them.
  const [byTerm, setByTerm] = useState(() => bands.some((b) => b.termFrom !== '' || b.termTo !== ''));
  const [withUnknown, setWithUnknown] = useState(() =>
    bands.some((b) => b.female.unknown !== '' || b.male.unknown !== ''),
  );
  const [fill, setFill] = useState({ from: '18', to: '65', every: '5' });

  const order = cellOrder(byTerm, withUnknown);
  const notes = bandNotes(bands);

  function write(next: Band[]) {
    setValue('baseRates', rowsFromBands(next), { shouldDirty: true });
  }

  function cellProps(row: number, cell: string, label: string) {
    const [head, tail] = cell.split('.') as [keyof Band, keyof Band['female'] | undefined];
    const band = bands[row]!;
    const value =
      tail && (head === 'female' || head === 'male') ? band[head][tail] : (band[head as 'ageFrom'] ?? '');
    return {
      value,
      inputSize: 'sm' as const,
      inputMode: 'decimal' as const,
      className: 'w-full text-right',
      'aria-label': label,
      onChange: (e: { target: { value: string } }) => write(editCell(bands, row, cell, e.target.value)),
      onPaste: (e: ClipboardEvent<HTMLInputElement>) => {
        const text = e.clipboardData.getData('text');
        if (!isBlock(text)) return;
        e.preventDefault();
        write(pasteBlock(bands, row, cell, text, order));
      },
    };
  }

  function addBand() {
    const last = bands.at(-1);
    const from = last && last.ageTo !== '' ? String(Number(last.ageTo) + 1) : '';
    write([...bands, { ...blankBand(from), termFrom: last?.termFrom ?? '', termTo: last?.termTo ?? '' }]);
  }

  function rowError(i: number): string | undefined {
    const pick = (r: (typeof errors.baseRates & object)[number] | undefined) =>
      r?.ageFrom?.message ?? r?.ageTo?.message ?? r?.termFromMonths?.message ?? r?.termToMonths?.message ??
      r?.nonSmoker?.message ?? r?.smoker?.message ?? r?.unknown?.message;
    return pick(errors.baseRates?.[2 * i]) ?? pick(errors.baseRates?.[2 * i + 1]);
  }

  const rateLabels = withUnknown ? ['Non-smoker', 'Smoker', "Didn't say"] : ['Non-smoker', 'Smoker'];
  const rateKeys = withUnknown ? (['nonSmoker', 'smoker', 'unknown'] as const) : (['nonSmoker', 'smoker'] as const);
  const columns = 2 + (byTerm ? 2 : 0) + rateKeys.length * 2 + 1;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          Ages from
          <Input inputSize="sm" inputMode="numeric" className="w-16 text-right" value={fill.from}
            onChange={(e) => setFill({ ...fill, from: e.target.value })} />
        </label>
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          to
          <Input inputSize="sm" inputMode="numeric" className="w-16 text-right" value={fill.to}
            onChange={(e) => setFill({ ...fill, to: e.target.value })} />
        </label>
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          every
          <Input inputSize="sm" inputMode="numeric" className="w-16 text-right" value={fill.every}
            onChange={(e) => setFill({ ...fill, every: e.target.value })} />
        </label>
        <Button
          type="button"
          size="sm"
          variant="secondary"
          // Replaces the table only while it is empty; once rates are typed, bands are added one at a time.
          disabled={bands.some((b) => Object.values(b.female).some(Boolean) || Object.values(b.male).some(Boolean))}
          onClick={() => write(generateBands(Number(fill.from), Number(fill.to), Number(fill.every)))}
        >
          Fill bands
        </Button>
        <div className="ml-auto flex flex-wrap gap-4">
          <CheckboxField
            label="Rates differ by term"
            checked={byTerm}
            onChange={(e) => {
              setByTerm(e.target.checked);
              if (!e.target.checked) write(bands.map((b) => ({ ...b, termFrom: '', termTo: '' })));
            }}
          />
          <CheckboxField
            label="Price smokers who didn't say"
            checked={withUnknown}
            onChange={(e) => {
              setWithUnknown(e.target.checked);
              if (!e.target.checked) {
                write(bands.map((b) => ({ ...b, female: { ...b.female, unknown: '' }, male: { ...b.male, unknown: '' } })));
              }
            }}
          />
        </div>
      </div>

      {bands.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full table-fixed border-separate border-spacing-x-1 border-spacing-y-1 text-sm" aria-label="Base rates">
            <thead>
              <tr className="text-xs text-muted-foreground">
                <th colSpan={2} className="text-left font-medium">Ages</th>
                {byTerm && <th colSpan={2} className="text-left font-medium">Term (months)</th>}
                <th colSpan={rateKeys.length} className="text-left font-medium">Female</th>
                <th colSpan={rateKeys.length} className="text-left font-medium">Male</th>
                <th className="w-8" aria-hidden />
              </tr>
              <tr className="text-xs text-subtle-foreground">
                <th className="text-right font-normal">From</th>
                <th className="text-right font-normal">To</th>
                {byTerm && (
                  <>
                    <th className="text-right font-normal">From</th>
                    <th className="text-right font-normal">To</th>
                  </>
                )}
                {[...rateLabels, ...rateLabels].map((l, i) => (
                  <th key={i} className="text-right font-normal">{l}</th>
                ))}
                <th aria-hidden />
              </tr>
            </thead>
            <tbody>
              {bands.map((band, i) => {
                const name = band.ageFrom || band.ageTo ? `Ages ${band.ageFrom}–${band.ageTo}` : `Band ${i + 1}`;
                const message = rowError(i) ?? notes[i];
                return [
                  <tr key={`band-${i}`}>
                    <td><Input {...cellProps(i, 'ageFrom', `${name}, from age`)} inputMode="numeric" /></td>
                    <td><Input {...cellProps(i, 'ageTo', `${name}, to age`)} inputMode="numeric" /></td>
                    {byTerm && (
                      <>
                        <td><Input {...cellProps(i, 'termFrom', `${name}, term from months`)} inputMode="numeric" /></td>
                        <td><Input {...cellProps(i, 'termTo', `${name}, term to months`)} inputMode="numeric" /></td>
                      </>
                    )}
                    {(['female', 'male'] as const).flatMap((sex) =>
                      rateKeys.map((k, j) => (
                        <td key={`${sex}-${k}`}>
                          <Input {...cellProps(i, `${sex}.${k}`, `${name}, ${sex} ${rateLabels[j]!.toLowerCase()} rate`)} />
                        </td>
                      )),
                    )}
                    <td>
                      <Button type="button" size="icon" variant="ghost" aria-label={`Remove ${name.toLowerCase()}`}
                        onClick={() => write(bands.filter((_, k) => k !== i))}>
                        <X />
                      </Button>
                    </td>
                  </tr>,
                  message ? (
                    <tr key={`note-${i}`}>
                      <td colSpan={columns}
                        className={rowError(i) ? 'px-1 text-xs text-status-danger-fg' : 'px-1 text-xs text-status-warning-fg'}
                        role={rowError(i) ? 'alert' : undefined}>
                        {message}
                      </td>
                    </tr>
                  ) : null,
                ];
              })}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={addBand}>
          <Plus /> Add band
        </Button>
        <p className="text-xs text-subtle-foreground">Rate a year per 1,000 of sum assured. Paste from Excel into any cell.</p>
      </div>
      {(errors.baseRates?.message || errors.baseRates?.root?.message) && (
        <p role="alert" className="text-xs text-status-danger-fg">{errors.baseRates?.message ?? errors.baseRates?.root?.message}</p>
      )}
    </div>
  );
}
