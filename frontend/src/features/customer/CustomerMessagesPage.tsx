import { Mail, MailOpen } from 'lucide-react';
import { useEffect, useState } from 'react';
import { getCustomerMessages, markMessageRead, type CustomerMessage } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';

import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';
import { cn } from '@/lib/cn';
import { useWords } from './words';

/**
 * The customer's messages (2026-10-08, the customer portal design step 7; PRD §31): what we sent by SMS and email,
 * readable here whether or not it reached their phone. Opening one marks it read; the text is as it was sent.
 */
export function CustomerMessagesPage() {
  const w = useWords().portal.messages;
  const [messages, setMessages] = useState<CustomerMessage[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    getCustomerMessages().then(
      (m) => { if (live) { setMessages(m); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  function toggle(message: CustomerMessage) {
    setOpen((current) => (current === message.messageId ? null : message.messageId));
    if (!message.read) {
      // Marked read as it opens; a failure only means it stays bold, so it is not reported.
      markMessageRead(message.messageId).then(
        (updated) => setMessages((all) => all?.map((m) => (m.messageId === updated.messageId ? updated : m)) ?? null),
        () => undefined,
      );
    }
  }

  return (
    <>
      <PageHeader title={w.title} description={w.lead} />
      <div className="w-full max-w-3xl px-4 pb-10 text-base sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !messages ? (
          <LoadingBlock label="Loading your messages" />
        ) : (
          <div className="overflow-hidden rounded-2xl border border-border bg-surface shadow-card">
            {messages.length === 0 ? (
              <EmptyState title={w.none} description={w.noneLead} />
            ) : (
              <ul className="divide-y divide-border">
                {messages.map((m) => {
                  const expanded = open === m.messageId;
                  return (
                    <li key={m.messageId}>
                      <button type="button" aria-expanded={expanded} onClick={() => toggle(m)}
                        className={cn('flex w-full items-start gap-3 px-4 py-4 text-left hover:bg-hover', !m.read && 'bg-selected/40')}>
                        {m.read
                          ? <MailOpen className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
                          : <Mail className="mt-0.5 size-5 shrink-0 text-accent" aria-label={w.fresh} />}
                        <span className="min-w-0 flex-1">
                          <span className={cn('block', !m.read && 'font-semibold')}>{m.title}</span>
                          <span className="block text-sm text-muted-foreground">
                            {formatInstant(m.sentAt)}{m.policyNumber && <> · <span className="font-mono">{m.policyNumber}</span></>}
                          </span>
                          {expanded && (
                            <span className="mt-2 block whitespace-pre-line">
                              {m.body ?? w.notKept}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        )}
      </div>
    </>
  );
}
