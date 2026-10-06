import { Select } from '@/components/ui/input';
import { useReferenceCodes } from '@/store/refdataStore';

/**
 * The branches a sale can belong to (refdata BRANCH, IFRS 17 I2), as a CONTROLLED select. Controlled on purpose: the
 * options arrive after the first render, and an uncontrolled select whose value is set before its options exist falls
 * back to the first option the browser sees -- a form would then submit whichever branch sorts first.
 *
 * @param allowNone offer an empty choice ("not named yet") -- for a case, where the server may default one
 */
export function BranchSelect({
  value,
  onChange,
  allowNone = false,
  noneLabel = 'Not named yet',
  id,
  disabled,
}: {
  value: string;
  onChange: (code: string) => void;
  allowNone?: boolean;
  noneLabel?: string;
  id?: string;
  disabled?: boolean;
}) {
  const branches = useReferenceCodes('BRANCH');
  // While the list loads, the current value is still offered, so the select shows it rather than nothing.
  const showValueAlone = value !== '' && !branches.some((b) => b.code === value);
  return (
    <Select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
      {allowNone && <option value="">{noneLabel}</option>}
      {showValueAlone && <option value={value}>{value}</option>}
      {branches.map((b) => (
        <option key={b.code} value={b.code}>
          {b.label} ({b.code})
        </option>
      ))}
    </Select>
  );
}
