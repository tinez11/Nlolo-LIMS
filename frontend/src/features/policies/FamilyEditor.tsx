import { Plus, X } from 'lucide-react';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select } from '@/components/ui/input';
import { FUNERAL_ROLE_LABELS } from '@/features/products/funeralSchema';
import { blankDependant, type DependantRole, type DependantRow, type FamilyRow } from './groupFuneral';

const DEPENDANT_ROLES: DependantRole[] = ['SPOUSE', 'CHILD', 'PARENT', 'EXTENDED'];

/**
 * One group funeral family being written (2026-10-07): the association's member number, the main
 * member and the beneficiary they name, and their dependants. Used by the proposal and by a family
 * joining an issued scheme.
 */
export function FamilyEditor({
  family,
  onChange,
  onRemove,
}: {
  family: FamilyRow;
  onChange: (next: FamilyRow) => void;
  onRemove?: (() => void) | undefined;
}) {
  const set = (change: Partial<FamilyRow>) => onChange({ ...family, ...change });
  const setDependant = (index: number, change: Partial<DependantRow>) =>
    set({ dependants: family.dependants.map((d, i) => (i === index ? { ...d, ...change } : d)) });

  return (
    <div className="rounded-md border border-border p-2.5" aria-label={`Family ${family.reference}`}>
      <div className="grid grid-cols-[7rem_1fr_10rem_auto] items-start gap-2">
        <FormField label="Member no.">
          <Input value={family.reference} onChange={(e) => set({ reference: e.target.value })} />
        </FormField>
        <FormField label="Main member">
          <Input value={family.mainName} placeholder="Full name" onChange={(e) => set({ mainName: e.target.value })} />
        </FormField>
        <FormField label="Date of birth">
          <DatePicker value={family.mainDateOfBirth || null} onChange={(iso) => set({ mainDateOfBirth: iso ?? '' })} />
        </FormField>
        <Button type="button" size="icon" variant="ghost" className="mt-5" aria-label={`Remove member ${family.reference}`}
          disabled={!onRemove} onClick={onRemove}>
          <X />
        </Button>
      </div>
      <div className="mt-2 grid gap-2 sm:grid-cols-3">
        <FormField label="Beneficiary (optional)">
          <Input value={family.beneficiaryName} onChange={(e) => set({ beneficiaryName: e.target.value })} />
        </FormField>
        <FormField label="Relationship">
          <Input value={family.beneficiaryRelationship} onChange={(e) => set({ beneficiaryRelationship: e.target.value })} />
        </FormField>
        <FormField label="Beneficiary phone">
          <Input value={family.beneficiaryPhone} onChange={(e) => set({ beneficiaryPhone: e.target.value })} />
        </FormField>
      </div>
      {family.dependants.map((d, j) => (
        <DependantFields key={j} dependant={d} onChange={(change) => setDependant(j, change)}
          onRemove={() => set({ dependants: family.dependants.filter((_, k) => k !== j) })} />
      ))}
      <Button type="button" size="sm" variant="ghost" className="mt-1 -ml-2"
        onClick={() => set({ dependants: [...family.dependants, blankDependant()] })}>
        <Plus />
        Add family member
      </Button>
    </div>
  );
}

/** One dependant's role, name, date of birth and student flag. */
export function DependantFields({
  dependant,
  onChange,
  onRemove,
}: {
  dependant: DependantRow;
  onChange: (change: Partial<DependantRow>) => void;
  onRemove?: (() => void) | undefined;
}) {
  return (
    <div className="mt-2 grid grid-cols-[9rem_1fr_10rem_auto_auto] items-end gap-2">
      <FormField label="Role">
        <Select value={dependant.role} onChange={(e) => onChange({ role: e.target.value as DependantRole })}>
          {DEPENDANT_ROLES.map((r) => <option key={r} value={r}>{FUNERAL_ROLE_LABELS[r]}</option>)}
        </Select>
      </FormField>
      <FormField label="Name">
        <Input value={dependant.fullName} onChange={(e) => onChange({ fullName: e.target.value })} />
      </FormField>
      <FormField label="Date of birth">
        <DatePicker value={dependant.dateOfBirth || null} onChange={(iso) => onChange({ dateOfBirth: iso ?? '' })} />
      </FormField>
      <CheckboxField label="Student" className="mb-1" checked={dependant.student} disabled={dependant.role !== 'CHILD'}
        onChange={(e) => onChange({ student: e.target.checked })} />
      {onRemove ? (
        <Button type="button" size="icon" variant="ghost" aria-label={`Remove ${dependant.fullName || 'dependant'}`}
          onClick={onRemove}>
          <X />
        </Button>
      ) : <span />}
    </div>
  );
}
