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
  roles = DEPENDANT_ROLES,
}: {
  family: FamilyRow;
  onChange: (next: FamilyRow) => void;
  onRemove?: (() => void) | undefined;
  /**
   * The dependant roles the chosen plan covers (2026-10-19). It offered all four whatever the plan, so a plan with no
   * extended-family benefit still offered extended family, and only the proposal refused it -- the whole scheme, at
   * the end. Every role where the caller does not know the plan.
   */
  roles?: DependantRole[] | undefined;
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
        <DependantFields key={j} dependant={d} roles={roles} onChange={(change) => setDependant(j, change)}
          onRemove={() => set({ dependants: family.dependants.filter((_, k) => k !== j) })} />
      ))}
      {/* A plan for the main member alone has no family to add: the button is not offered rather than refused. */}
      {roles.length > 0 ? (
        <Button type="button" size="sm" variant="ghost" className="mt-1 -ml-2"
          onClick={() => set({ dependants: [...family.dependants, { ...blankDependant(), role: roles[0] as DependantRole }] })}>
          <Plus />
          Add family member
        </Button>
      ) : (
        <p className="mt-2 text-xs text-muted-foreground">This plan covers the main member only.</p>
      )}
    </div>
  );
}

/** One dependant's role, name, date of birth and student flag. */
export function DependantFields({
  dependant,
  onChange,
  onRemove,
  roles = DEPENDANT_ROLES,
}: {
  dependant: DependantRow;
  onChange: (change: Partial<DependantRow>) => void;
  onRemove?: (() => void) | undefined;
  /** The roles the plan covers; every role where the caller does not know the plan. */
  roles?: DependantRole[] | undefined;
}) {
  // A row already holding a role the plan does not cover keeps it in its own list, so the select never shows one
  // role while the row holds another -- the problem stays visible, and the quote names it.
  const options = roles.includes(dependant.role) ? roles : [...roles, dependant.role];
  return (
    <div className="mt-2 grid grid-cols-[9rem_1fr_10rem_auto_auto] items-end gap-2">
      <FormField label="Role">
        <Select value={dependant.role} onChange={(e) => onChange({ role: e.target.value as DependantRole })}>
          {options.map((r) => <option key={r} value={r}>{FUNERAL_ROLE_LABELS[r]}</option>)}
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
