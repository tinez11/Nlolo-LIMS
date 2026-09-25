import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';

/**
 * One editable beneficiary: who they are, what share, and whether it can be revoked.
 *
 * Extracted because the markup below existed TWICE, byte-for-byte, in
 * `BeneficiariesPanel` and `IssuePolicyPage` -- the same copy-paste habit `Panel`,
 * `Field`, `FormField` and the input primitives each cured before it. The two copies
 * had already drifted: the issue page rendered no per-row validation error at all, so
 * a rejected row there showed nothing beside the field that caused it.
 *
 * **The layout is the fix, not a preference.** The four controls used to sit on one
 * flex row, and the designee field measured **18 pixels wide inside a 666px form** --
 * unusable, and reported as such. The cause is worth recording because it will recur:
 * every control here inherits `w-full` from the shared `field` base class, so the
 * `<select>`'s flex basis resolved to `auto` (i.e. 100%) while the designee input's
 * `flex-1` gave it a basis of `0`. A flex item shrinks in proportion to its basis, so
 * an item with basis `0` cannot shrink -- and cannot claim space either. All the
 * overflow was therefore absorbed by the input that mattered most, and the select,
 * holding two words, kept ~450px of it.
 *
 * So the naming of who this is gets its own line and the whole remaining width, and
 * the two small facts about the share sit under it. `shrink-0` on the fixed-width
 * controls and `min-w-0` on the growing one are what stop the same collapse
 * reappearing.
 *
 * Sizes are the platform's default `md` (h-9), not the `sm` this row used to use: the
 * `PartyPicker` in the party branch is `h-9` and always was, so the old `sm` select
 * sat 4px shorter than the field beside it, inside an `h-8` wrapper that clipped it.
 *
 * `party` is a slot rather than a prop bundle because the picker needs a
 * form-typed `Controller`, and that belongs at the call site where the form's own
 * generics are known. Everything else arrives as a plain `register()` return, which
 * is why this component needs no generics of its own.
 */
export function BeneficiaryRow({
  type,
  typeField,
  designeeField,
  shareField,
  revocableField,
  party,
  onRemove,
  error,
}: {
  type: 'PARTY' | 'FREEFORM';
  typeField: UseFormRegisterReturn;
  designeeField: UseFormRegisterReturn;
  shareField: UseFormRegisterReturn;
  revocableField: UseFormRegisterReturn;
  /** The `Controller`-wrapped `PartyPicker`, rendered only when `type` is PARTY. */
  party: ReactNode;
  onRemove: () => void;
  error?: string | undefined;
}) {
  return (
    <div className="rounded-md border border-border p-3">
      <div className="flex items-center gap-2">
        {/* w-32 beats the base `w-full` through tailwind-merge; without an explicit
            width this select claims the whole row (see the header note). */}
        <Select aria-label="Beneficiary type" className="w-32 shrink-0" {...typeField}>
          <option value="PARTY">Party</option>
          <option value="FREEFORM">Freeform</option>
        </Select>

        {/* min-w-0 is load-bearing: a flex item's default `min-width: auto` refuses to
            shrink below its content, which is how a long designee name would push the
            share box off the row instead of truncating inside its own field. */}
        <div className="min-w-0 flex-1">
          {type === 'PARTY' ? (
            party
          ) : (
            <Input
              aria-label="Freeform designee"
              // Unchanged wording: several e2e specs address this field by its
              // placeholder, and it is also the only hint of what a freeform
              // designee is for.
              placeholder={'Designee, e.g. "My Estate"'}
              {...designeeField}
            />
          )}
        </div>

        <Button
          type="button"
          size="icon"
          variant="ghost"
          // Left exactly as it was. An ordinal ("Remove beneficiary 2") would read
          // better to a screen reader picking one of several rows, and is worth doing
          // deliberately rather than as a side effect of a layout fix -- with the
          // `remove.first()` loops in the specs revisited at the same time, since they
          // would then be removing whichever row happens to sort first by name.
          aria-label="Remove beneficiary"
          className="shrink-0"
          onClick={onRemove}
        >
          <X />
        </Button>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-2">
        <div className="flex items-center gap-1.5">
          <Input
            type="number"
            min={0}
            max={100}
            step="0.01"
            aria-label="Share percent"
            className="w-24 text-right"
            {...shareField}
          />
          <span className="text-xs text-muted-foreground">% share</span>
        </div>

        {/* A real wrapping <label>, so the checkbox is named by the word beside it
            without an id having to be threaded through. */}
        <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <input type="checkbox" {...revocableField} />
          Revocable
        </label>
      </div>

      {error && <p className="mt-2 text-xs text-status-danger-fg">{error}</p>}
    </div>
  );
}
