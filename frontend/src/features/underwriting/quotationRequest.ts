import type { QuotationRequest } from '@/api/documents';
import { normalisePhone } from '@/lib/patterns';
import type { PricingBasis, ProspectInputs } from './pricingBasis';
import type { FuneralDependantInput } from './useFuneralQuotation';
import { isAmount, isWholeMonths, type QuotationInputs } from './useQuotation';

/** What the quotation panel holds: the form's inputs, and who is quoted. */
export interface QuotationForm {
  inputs: QuotationInputs;
  premiumPayingTermMonths: string;
  commencementDate: string;
  productVersionId: string;
  applicantPartyId: string;
  prospect?: ProspectInputs | undefined;
  funeral?:
    | { planCode: string; mainMember: { name: string; dateOfBirth: string | null } | null; dependants: FuneralDependantInput[] }
    | undefined;
}

/**
 * The quotation's INPUTS, as the form holds them -- never a figure from the screen.
 *
 * <p>Null until there is enough to ask: a product, an applicant, and what that basis cannot be quoted without. The
 * server re-checks all of it; this only keeps a button from sending a request that is certain to be refused. Shared
 * (2026-10-20) by the PDF download and the agents' "customer agrees" save, so the paper and the saved quotation are
 * asked for with exactly the same inputs.
 */
export function quotationRequest(basis: PricingBasis, form: QuotationForm): QuotationRequest | null {
  const { inputs, productVersionId, applicantPartyId, prospect } = form;
  if (!inputs.productId || !productVersionId) return null;
  // An optional phone that is not a Tanzanian number is held back like any other malformed field: it would be
  // printed on the paper and carried into onboarding as typed.
  const phone = prospect?.phoneNumber.trim() ? normalisePhone(prospect.phoneNumber) : null;
  if (!applicantPartyId && prospect?.phoneNumber.trim() && !phone) return null;
  // Who is quoted: a registered applicant by id, else a prospect by name -- never both, which the server refuses.
  const who = applicantPartyId
    ? { applicantPartyId }
    : prospect && prospect.fullName.trim()
      ? {
          prospect: {
            fullName: prospect.fullName.trim(),
            phoneNumber: phone,
            dateOfBirth: prospect.dateOfBirth || null,
            sex: prospect.sex || null,
            smokerStatus: prospect.smokerStatus || null,
            occupationClass: prospect.occupationClass || null,
          },
        }
      : null;
  if (!who) return null;
  const base = {
    productId: inputs.productId,
    productVersionId,
    ...who,
    currency: inputs.sumAssuredCurrency || 'TZS',
    commencement: form.commencementDate || null,
  };

  if (basis === 'FUNERAL') {
    const main = form.funeral?.mainMember;
    if (!form.funeral?.planCode || !inputs.frequency || !main?.dateOfBirth) return null;
    return {
      ...base,
      frequency: inputs.frequency,
      funeralPlanCode: form.funeral.planCode,
      lives: [
        { role: 'MAIN_MEMBER', name: main.name, dateOfBirth: main.dateOfBirth, student: false },
        ...form.funeral.dependants.map((d) => ({
          role: d.role as 'SPOUSE' | 'CHILD' | 'PARENT' | 'EXTENDED',
          name: d.fullName,
          dateOfBirth: d.dateOfBirth || null,
          student: d.student,
        })),
      ],
    };
  }

  // Malformed numbers are not sent: "1,000,000" or "10 years" would reach the server as nulls and come back as a
  // refusal about something else. The form says what is wrong beside the field.
  if (!isAmount(inputs.sumAssuredAmount)) return null;
  if (inputs.policyTermMonths && !isWholeMonths(inputs.policyTermMonths)) return null;
  if (form.premiumPayingTermMonths && !isWholeMonths(form.premiumPayingTermMonths)) return null;
  if (basis === 'PREMIUM' && !inputs.frequency) return null;
  return {
    ...base,
    sumAssured: Number(inputs.sumAssuredAmount),
    frequency: inputs.frequency || null,
    policyTermMonths: inputs.policyTermMonths ? Number(inputs.policyTermMonths) : null,
    premiumPayingTermMonths: form.premiumPayingTermMonths ? Number(form.premiumPayingTermMonths) : null,
  };
}
