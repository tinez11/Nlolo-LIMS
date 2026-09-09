/**
 * `OFFER_MADE` reads as "Offer made".
 *
 * The keys are for code -- they are what the sender looks a template up by and what the outbox
 * stores -- but every screen that shows one is showing it to a person. Its own module rather than
 * an export from a component file, which react-refresh rightly objects to.
 */
export function humanizeTemplateKey(key: string | undefined): string {
  if (!key) return '—';
  const words = key.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
