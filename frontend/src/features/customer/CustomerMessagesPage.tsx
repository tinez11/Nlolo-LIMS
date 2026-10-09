import { Mail, MailOpen } from 'lucide-react';
import { useEffect, useState } from 'react';
import { getCustomerMessages, markMessageRead, type CustomerMessage } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';
import { cn } from '@/lib/cn';

/**
 * The customer's messages (2026-10-08, the customer portal design step 7; PRD §31): what we sent by SMS and email,
 * readable here whether or not it reached their phone. Opening one marks it read; the text is as it was sent.
 */
export function CustomerMessagesPage() {
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
      <PageHeader title="Messages" description="What we have sent you by SMS and email." />
      <div className="px-4 pb-8 sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !messages ? (
          <LoadingBlock label="Loading your messages" />
        ) : (
          <Panel title="Inbox">
            {messages.length === 0 ? (
              <EmptyState title="No messages" description="Messages about your policies and payments appear here." />
            ) : (
              <ul className="divide-y divide-border">
                {messages.map((m) => {
                  const expanded = open === m.messageId;
                  return (
                    <li key={m.messageId}>
                      <button type="button" aria-expanded={expanded} onClick={() => toggle(m)}
                        className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-hover">
                        {m.read
                          ? <MailOpen className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-label="Read" />
                          : <Mail className="mt-0.5 size-4 shrink-0 text-accent" aria-label="New" />}
                        <span className="min-w-0 flex-1">
                          <span className={cn('block text-sm', !m.read && 'font-semibold')}>{m.title}</span>
                          <span className="block text-xs text-muted-foreground">
                            {formatInstant(m.sentAt)}{m.policyNumber && <> · <span className="font-mono">{m.policyNumber}</span></>}
                          </span>
                          {expanded && (
                            <span className="mt-2 block whitespace-pre-line text-sm">
                              {m.body ?? 'The text of this message was not kept. Contact us if you need it again.'}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </Panel>
        )}
      </div>
    </>
  );
}
