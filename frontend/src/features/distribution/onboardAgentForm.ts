import { z } from 'zod';
import type { OnboardAgentRequest } from '@/api/types';
import { todayIso } from '@/lib/dates';
import { ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for `POST /agents`, mirroring `OnboardAgentRequestDto` and
 * `DistributionApiImpl.onboardAgent`'s own checks exactly (transcribed from
 * source, not guessed from the spec): licence expiry must genuinely be in the
 * future, and 50 characters is `agent_profile.license_number`'s real column
 * limit, checked in code precisely so an over-long value 422s cleanly instead
 * of reaching Postgres as a `DataIntegrityViolationException`.
 *
 * Two real backend checks this form does NOT attempt to mirror, because
 * nothing client-side can know the answer: the party must exist and have KYC
 * status VERIFIED, and a supplied hierarchy parent must exist in this tenant.
 * Both surface as real 422s from the server.
 */
const MAX_LICENSE_NUMBER_LENGTH = 50;

/** The channels an intermediary can sell through; DIRECT and DIGITAL are sales no agent made. */
export const AGENT_CHANNELS = ['AGENT', 'BROKER', 'BANCASSURANCE'] as const;

export const onboardAgentFormSchema = z.object({
  partyId: z.string().trim().min(1, 'Party id is required').regex(UUID_PATTERN, 'Not a valid party id'),
  licenseNumber: z.string().trim().min(1, 'A license number is required').max(
    MAX_LICENSE_NUMBER_LENGTH,
    `Cannot exceed ${MAX_LICENSE_NUMBER_LENGTH} characters`,
  ),
  licenseExpiryDate: z
    .string()
    .trim()
    .min(1, 'License expiry date is required')
    .regex(ISO_DATE_PATTERN, 'Not a valid date')
    .refine((v) => !ISO_DATE_PATTERN.test(v) || v > todayIso(), 'Must be in the future'),
  hierarchyParentId: z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid agent id'),
  // IFRS 17 I2: the channel this intermediary sells through and the branch it sells from. A case the agent
  // introduces takes both as its defaults.
  salesChannel: z.enum(AGENT_CHANNELS),
  homeBranch: z.string().trim().min(1, 'Choose the branch the agent sells from'),
});

export type OnboardAgentFormValues = z.infer<typeof onboardAgentFormSchema>;

export function blankOnboardAgentForm(): OnboardAgentFormValues {
  return {
    partyId: '',
    licenseNumber: '',
    licenseExpiryDate: '',
    hierarchyParentId: '',
    salesChannel: 'AGENT',
    homeBranch: 'DSM',
  };
}

export function toApiRequest(values: OnboardAgentFormValues): OnboardAgentRequest {
  return {
    partyId: values.partyId.trim(),
    licenseNumber: values.licenseNumber.trim(),
    licenseExpiryDate: values.licenseExpiryDate,
    hierarchyParentId: values.hierarchyParentId.trim() || null,
    salesChannel: values.salesChannel,
    homeBranch: values.homeBranch,
  };
}

/** `PUT /agents/{agentId}/placement` (IFRS 17 I2): both required. */
export const agentPlacementSchema = z.object({
  salesChannel: z.enum(AGENT_CHANNELS),
  homeBranch: z.string().trim().min(1, 'Choose the branch the agent sells from'),
});
export type AgentPlacementValues = z.infer<typeof agentPlacementSchema>;
