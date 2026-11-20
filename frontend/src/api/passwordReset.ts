import { post } from '@/lib/http';

/**
 * The public password reset at /reset (2026-11-19, Authentik cut-over ticket 07): no login. Each call carries the
 * visit that Turnstile started (POST /public/visits, shared with the quote link) in X-Visit.
 */
export interface RecoveryLink {
  link: string;
}

/** Texts a code when the number has an agent's or customer's login; answers the same either way. */
export function requestResetCode(phoneNumber: string, language: 'en' | 'sw', visitToken: string): Promise<void> {
  return post<void>('/public/password-reset', { phoneNumber, language }, { headers: { 'X-Visit': visitToken } });
}

/** The right code is exchanged for Authentik's one-time link to choose a new password. */
export function checkResetCode(phoneNumber: string, code: string, language: 'en' | 'sw',
                               visitToken: string): Promise<RecoveryLink> {
  return post<RecoveryLink>('/public/password-reset/check', { phoneNumber, code, language },
    { headers: { 'X-Visit': visitToken } });
}
