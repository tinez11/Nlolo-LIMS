import type { ClaimDetails } from '@/api/types';
import { Field } from '@/components/Field';
import { formatDate } from '@/lib/dates';

/**
 * Renders the claim-type-specific fields of `ClaimDetails`.
 *
 * The spec has no `discriminator` keyword on this union (deliberately -- see
 * ClaimDetails's own comment in api/types.ts), but every subtype's `claimType` is
 * a literal enum of exactly one value, so this `switch` narrows the union
 * natively. No manual type guard needed, and TypeScript itself flags a missing
 * branch if a 5th claim type is ever added.
 */
export function ClaimDetailsFields({ details }: { details: ClaimDetails }) {
  switch (details.claimType) {
    case 'DEATH':
      return (
        <>
          <Field label="Cause of death" value={details.causeOfDeath} />
          <Field label="Place of death" value={details.placeOfDeath} />
          <Field label="Date of death" value={formatDate(details.dateOfDeath)} />
          <Field label="Attending physician" value={details.attendingPhysician} />
        </>
      );
    case 'DISABILITY':
      return (
        <>
          <Field label="Disability type" value={details.disabilityType} />
          <Field label="Onset date" value={formatDate(details.onsetDate)} />
          <Field label="Permanent" value={details.permanent ? 'Yes' : 'No'} />
          <Field label="Impairment" value={`${details.impairmentPercent}%`} />
        </>
      );
    case 'CRITICAL_ILLNESS':
      return (
        <>
          <Field label="Diagnosis" value={details.diagnosis} />
          <Field label="Diagnosis date" value={formatDate(details.diagnosisDate)} />
          <Field label="ICD code" value={<span className="font-mono text-xs">{details.icdCode}</span>} />
        </>
      );
    case 'MATURITY':
      return <Field label="Maturity date" value={formatDate(details.maturityDate)} />;
  }
}
