/**
 * Where the bare site address goes (2026-10-13, the user's D8).
 *
 * Development shows the realm picker, which is the right tool for someone who signs in as four different kinds of
 * user in an afternoon. Production opens the customer portal: that address is what goes on an SMS invite, a letter
 * and the company's website, so the people who arrive at it are policyholders. Staff, agents and TIRA bookmark their
 * own paths, and are not advertised to the public alongside a list of logins that are not theirs.
 */
export const SHOW_REALM_PICKER = import.meta.env.VITE_REALM_PICKER !== 'false';

export function entryPath(showPicker: boolean): string | null {
  return showPicker ? null : '/customers';
}
