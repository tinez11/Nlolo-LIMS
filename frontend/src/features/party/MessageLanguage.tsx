import { useState } from 'react';
import { setPreferredLanguage } from '@/api/party';
import { can } from '@/auth/access';
import { InlineError } from '@/components/InlineError';
import { Select } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';

const WORDS = { sw: 'Swahili', en: 'English' } as const;

/**
 * The language a client's messages are written in (2026-11-08): Swahili unless set. Staff who may amend a client
 * change it here, in place -- every SMS and email from then on goes out in it.
 */
export function MessageLanguage({ partyId, language }: { partyId: string; language: string | null | undefined }) {
  const [current, setCurrent] = useState<'sw' | 'en'>(language === 'en' ? 'en' : 'sw');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  if (!can('clients.edit')) return <>{WORDS[current]}</>;

  async function change(next: 'sw' | 'en') {
    setBusy(true);
    setError(null);
    try {
      const saved = await setPreferredLanguage(partyId, next);
      setCurrent(saved.preferredLanguage === 'en' ? 'en' : 'sw');
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <span className="inline-flex flex-col gap-1">
      <Select inputSize="sm" aria-label="Messages in" value={current} disabled={busy}
        onChange={(e) => void change(e.target.value as 'sw' | 'en')}>
        <option value="sw">Swahili</option>
        <option value="en">English</option>
      </Select>
      {error && <InlineError error={error} />}
    </span>
  );
}
