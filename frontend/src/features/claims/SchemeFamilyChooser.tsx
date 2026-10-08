import { useState } from 'react';
import type { GroupFuneralFamilyView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { Input, Select } from '@/components/ui/input';
import { matchCount, searchEnter } from '@/lib/searchKeys';
import { familyLabel, matchFamilies } from './schemeLives';

/**
 * Which family on a group funeral scheme a death claim is for (2026-10-08): a search by member number or
 * main member's name over the families, and the family list it narrows. One match is chosen at once and
 * the search says what it found; Enter picks a single match and never submits the claim around it --
 * staff pressed Enter here and got "Choose who died" from a claim they had not finished.
 */
export function SchemeFamilyChooser({ families, family, onChoose }: {
  /** Null while loading. */
  families: GroupFuneralFamilyView[] | null;
  family: GroupFuneralFamilyView | null;
  onChoose: (policyMemberId: string | null) => void;
}) {
  const [query, setQuery] = useState('');
  const matches = matchFamilies(families ?? [], query);
  // The chosen family stays in the list even when the search no longer matches it.
  const choices = family && !matches.includes(family) ? [family, ...matches] : matches;
  const single = (found: GroupFuneralFamilyView[]) => (found.length === 1 ? found[0] ?? null : null);

  return (
    <>
      {/* Outside the FormField: FormField labels its first control, and that must be the family list. */}
      <Input
        inputSize="sm"
        placeholder="Search families by member number or name…"
        aria-label="Search families"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          const one = e.target.value.trim() ? single(matchFamilies(families ?? [], e.target.value)) : null;
          if (one) onChoose(one.policyMemberId);
        }}
        onKeyDown={searchEnter(() => {
          const one = query.trim() ? single(matches) : null;
          if (one) onChoose(one.policyMemberId);
        })}
      />
      {families !== null && query.trim() !== '' && (
        <p className="mt-1 text-xs text-subtle-foreground" role="status">
          {matches.length === 0
            ? <>No family matches &ldquo;{query.trim()}&rdquo;.</>
            : matches.length === 1
              ? `1 family matches — ${familyLabel(matches[0] as GroupFuneralFamilyView)}, chosen below.`
              : `${matchCount(matches.length, 'family', 'families')} — choose one below.`}
        </p>
      )}
      <FormField label="Family">
        <Select inputSize="sm" value={family?.policyMemberId ?? ''} onChange={(e) => onChoose(e.target.value || null)}>
          <option value="">{families === null ? 'Loading families…' : 'Choose the family…'}</option>
          {choices.map((f) => (
            <option key={f.policyMemberId} value={f.policyMemberId}>{familyLabel(f)}</option>
          ))}
        </Select>
      </FormField>
    </>
  );
}
