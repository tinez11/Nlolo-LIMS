import { can } from '@/auth/access';
import { useEffect, useMemo, useRef, useState } from 'react';
import { getDispatchSummary, sendTestMessage } from '@/api/communications';
import type { NotificationTemplateView, TestSendResult } from '@/api/types';
import { FilterChip } from '@/components/FilterChip';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useCommunicationsStore } from '@/store/communicationsStore';
import { ChannelStatusBar } from './ChannelStatusBar';
import {
  TOPICS,
  journeyOrder,
  messageName,
  messageTopic,
  placeholdersIn,
  previewPieces,
  previewText,
  sampleFor,
  samplesFor,
  smsParts,
  whenSent,
} from './messageCatalog';

type Language = 'sw' | 'en';
const LANGUAGE: Record<Language, string> = { sw: 'Swahili', en: 'English' };

/**
 * What this platform says to customers, and the one screen that can change it (2026-11-08 redesign, the user: "not
 * user feasible"). Each message reads as a customer will read it -- sample values filled in -- with when it is sent,
 * what an SMS costs in parts, and an editor that shows the result as it is typed and can send a test to the editor's
 * own phone or mailbox.
 *
 * <p>There is no "new template" and no delete, deliberately: the set of messages is defined by the code that sends
 * them. This screen changes wording.
 */
export function TemplatesPage() {
  const isAdmin = can('communications.templates');
  const templates = useCommunicationsStore((s) => s.templates);
  const loadTemplates = useCommunicationsStore((s) => s.loadTemplates);
  const [query, setQuery] = useState('');
  const [topic, setTopic] = useState<string | null>(null);
  // How often each message went out in the last 30 days (2026-11-08): the screen's own report.
  const [sent, setSent] = useState<Record<string, number>>({});
  useEffect(() => {
    getDispatchSummary(30).then(
      (s) => setSent(Object.fromEntries((s.byMessage ?? []).map((m) => [m.templateKey, m.count]))),
      () => setSent({}),
    );
  }, []);

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  const byKey = useMemo(() => {
    const map = new Map<string, NotificationTemplateView[]>();
    for (const t of templates.data ?? []) {
      const key = t.templateKey ?? 'UNKNOWN';
      map.set(key, [...(map.get(key) ?? []), t]);
    }
    return map;
  }, [templates.data]);

  if (isInitialLoad(templates)) return <LoadingBlock label="Loading templates…" />;
  if (templates.status === 'error' && templates.error && templates.data === null) {
    return <ErrorPanel error={templates.error} onRetry={() => void loadTemplates()} />;
  }
  if (byKey.size === 0) {
    return (
      <>
        <PageHeader title="Message templates" />
        <EmptyState
          title="No templates for this tenant"
          description="Until they exist, every message this platform tries to send will be recorded as failed."
        />
      </>
    );
  }

  const q = query.trim().toLowerCase();
  const keys = [...byKey.keys()].sort(journeyOrder).filter((key) => {
    if (topic && messageTopic(key) !== topic) return false;
    if (!q) return true;
    const texts = (byKey.get(key) ?? []).map((t) => t.bodyTemplate ?? '').join(' ');
    return `${messageName(key)} ${whenSent(key) ?? ''} ${texts}`.toLowerCase().includes(q);
  });
  const topics = [...TOPICS, 'Other'].filter((t) => [...byKey.keys()].some((k) => messageTopic(k) === t));

  return (
    <>
      <PageHeader
        title="Message templates"
        description="What customers are told, and when. Editing a message changes every one sent from then on."
      />
      <div className="space-y-4 px-6 pb-8">
        <ChannelStatusBar />
        <div className="flex flex-wrap items-center gap-2">
          <FilterChip label="All" active={topic === null} onClick={() => setTopic(null)} />
          {topics.map((t) => <FilterChip key={t} label={t} active={topic === t} onClick={() => setTopic(t)} />)}
          <div className="ml-auto w-full sm:w-72">
            <Input aria-label="Search messages" placeholder="Search messages" value={query}
              onChange={(e) => setQuery(e.target.value)} />
          </div>
        </div>
        {!isAdmin && <p className="text-xs text-muted-foreground">Changing a message needs an administrator.</p>}
        {keys.length === 0 ? (
          <EmptyState title="No message matches" description="Try another word, or clear the topic." />
        ) : (
          // One section per stage of the customer's journey, in order; within it, the most frequent first.
          [...TOPICS, 'Other' as const].filter((t) => keys.some((k) => messageTopic(k) === t)).map((t) => {
            const inStage = keys.filter((k) => messageTopic(k) === t);
            const total = inStage.reduce((n, k) => n + (sent[k] ?? 0), 0);
            return (
              <section key={t} aria-label={t} className="space-y-3">
                <div className="flex items-baseline justify-between gap-3 border-b border-border pb-1">
                  <h2 className="font-display text-title font-semibold text-heading">{t}</h2>
                  <span className="text-xs text-muted-foreground">{total.toLocaleString('en-US')} sent in the last 30 days</span>
                </div>
                <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                  {inStage.map((key) => (
                    <MessageCard key={key} templateKey={key} variants={byKey.get(key) ?? []} canEdit={isAdmin} sent={sent[key] ?? 0} />
                  ))}
                </div>
              </section>
            );
          })
        )}
      </div>
    </>
  );
}

function MessageCard({ templateKey, variants, canEdit, sent }: {
  templateKey: string;
  variants: NotificationTemplateView[];
  canEdit: boolean;
  /** How many went out in the last 30 days. */
  sent: number;
}) {
  const [language, setLanguage] = useState<Language>('sw');
  const inLanguage = variants.filter((v) => v.language === language)
    .sort((a, b) => (a.channel === 'SMS' ? -1 : 1) - (b.channel === 'SMS' ? -1 : 1));
  const when = whenSent(templateKey);
  return (
    <section className="flex flex-col rounded-xl border border-border bg-surface shadow-card" aria-label={messageName(templateKey)}>
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-border px-5 py-4">
        <div className="min-w-0">
          <h2 className="text-base font-semibold">{messageName(templateKey)}</h2>
          {when && <p className="mt-0.5 text-sm text-muted-foreground">Sent {when.charAt(0).toLowerCase()}{when.slice(1)}</p>}
          <p className="mt-1 text-xs font-medium text-label">{sent === 0 ? 'None sent in the last 30 days' : `${sent.toLocaleString('en-US')} sent in the last 30 days`}</p>
        </div>
        <div className="flex rounded-lg border border-border p-0.5" role="tablist" aria-label="Language">
          {(['sw', 'en'] as const).map((l) => (
            <button key={l} type="button" role="tab" aria-selected={language === l}
              className={cn('rounded-md px-3 py-1 text-xs font-medium', language === l ? 'bg-selected text-foreground' : 'text-muted-foreground')}
              onClick={() => setLanguage(l)}>
              {LANGUAGE[l]}
            </button>
          ))}
        </div>
      </header>
      <p className="px-5 pt-3 text-xs text-muted-foreground">
        {language === 'sw'
          ? 'Sent to every customer, unless their record says English.'
          : 'Sent to customers whose record says English.'}
      </p>
      <div className="grid flex-1 grid-cols-1 gap-3 p-5 pt-3">
        {inLanguage.length === 0
          ? <p className="text-sm text-muted-foreground">No {LANGUAGE[language]} wording yet; Swahili is sent instead.</p>
          : inLanguage.map((t) => <Variant key={t.templateId} template={t} canEdit={canEdit} />)}
      </div>
    </section>
  );
}

function Variant({ template, canEdit }: { template: NotificationTemplateView; canEdit: boolean }) {
  const [editing, setEditing] = useState(false);
  const body = template.bodyTemplate ?? '';
  const sms = template.channel === 'SMS';
  return (
    <div className="rounded-lg border border-border">
      <div className="flex items-center justify-between gap-2 border-b border-border bg-surface-muted px-3 py-2">
        <span className="text-eyebrow font-medium uppercase text-label">{sms ? 'SMS' : 'Email'}</span>
        {!editing && canEdit && <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>Edit</Button>}
      </div>
      {editing
        ? <Editor template={template} onDone={() => setEditing(false)} />
        : (
          <div className="space-y-2 p-3">
            <Preview body={body} sms={sms} />
            {sms && <PartsLine text={previewText(body)} />}
          </div>
        )}
    </div>
  );
}

/** The message as the customer reads it: sample values filled in and marked. */
function Preview({ body, sms }: { body: string; sms: boolean }) {
  const pieces = previewPieces(body);
  return (
    <p className={cn('whitespace-pre-wrap text-sm leading-relaxed',
      sms ? 'max-w-prose rounded-2xl rounded-tl-sm bg-selected px-4 py-3' : 'rounded-md bg-surface-muted px-4 py-3')}
      aria-label="Preview">
      {pieces.map((p, i) => p.value
        ? <mark key={i} title={`Filled in: ${p.name}`} className="rounded bg-status-pending-bg px-0.5 font-medium text-foreground">{p.text}</mark>
        : <span key={i}>{p.text}</span>)}
    </p>
  );
}

/** What an SMS costs: each part is billed as one message. */
function PartsLine({ text }: { text: string }) {
  const { characters, parts, unicode } = smsParts(text);
  return (
    <p className={cn('text-xs', parts > 1 ? 'text-status-warning-fg' : 'text-muted-foreground')}>
      About {characters} characters · {parts === 1 ? '1 SMS' : `${parts} SMS parts, each billed`}
      {unicode && ' · a special character makes each part shorter (70)'}
    </p>
  );
}

function Editor({ template, onDone }: { template: NotificationTemplateView; onDone: () => void }) {
  const original = template.bodyTemplate ?? '';
  const [body, setBody] = useState(original);
  const reword = useCommunicationsStore((s) => s.reword);
  const rewording = useCommunicationsStore((s) => s.rewording);
  const area = useRef<HTMLTextAreaElement>(null);
  const sms = template.channel === 'SMS';
  const available = template.placeholders ?? placeholdersIn(original);
  const removed = placeholdersIn(original).filter((p) => !placeholdersIn(body).includes(p));
  const unknown = placeholdersIn(body).filter((p) => !available.includes(p));
  const busy = rewording.status === 'loading';

  function insert(name: string) {
    const el = area.current;
    const token = `{{${name}}}`;
    if (!el) {
      setBody((b) => b + token);
      return;
    }
    const start = el.selectionStart ?? body.length;
    const end = el.selectionEnd ?? body.length;
    setBody(body.slice(0, start) + token + body.slice(end));
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + token.length, start + token.length);
    });
  }

  async function save() {
    await reword(template.templateId ?? '', body);
    if (useCommunicationsStore.getState().rewording.status === 'success') onDone();
  }

  return (
    <div className="space-y-3 p-3">
      <div>
        <p className="mb-1 text-xs font-medium text-label">Values you can use — click to insert</p>
        <div className="flex flex-wrap gap-1.5">
          {available.map((name) => (
            <button key={name} type="button" onClick={() => insert(name)}
              className="rounded-md border border-border bg-surface px-2 py-0.5 text-xs hover:bg-hover"
              title={`Inserts {{${name}}}, e.g. ${sampleFor(name)}`}>
              {humanise(name)}
            </button>
          ))}
        </div>
      </div>
      <textarea ref={area} aria-label={`${messageName(template.templateKey)} ${template.channel} ${template.language} wording`}
        className="w-full rounded-md border border-border bg-background p-2 font-mono text-sm" rows={4}
        value={body} onChange={(e) => setBody(e.target.value)} />
      {removed.length > 0 && (
        <p role="alert" className="rounded-md bg-status-warning-bg px-3 py-2 text-xs text-status-warning-fg">
          This no longer includes {removed.map(humanise).join(', ')}. Customers will not be told {removed.length === 1 ? 'it' : 'them'}.
        </p>
      )}
      {unknown.length > 0 && (
        <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
          {unknown.map((u) => `{{${u}}}`).join(', ')} is not a value this message has; it cannot be saved.
        </p>
      )}
      <div>
        <p className="mb-1 text-xs font-medium text-label">How the customer will read it</p>
        <Preview body={body} sms={sms} />
        {sms && <div className="mt-1"><PartsLine text={previewText(body)} /></div>}
      </div>
      {rewording.status === 'error' && rewording.error && <InlineError error={rewording.error} />}
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="primary" disabled={busy || unknown.length > 0 || body.trim() === '' || body === original}
          onClick={() => void save()}>
          {busy ? 'Saving…' : 'Save wording'}
        </Button>
        <Button size="sm" disabled={busy} onClick={() => { setBody(original); onDone(); }}>Cancel</Button>
      </div>
      <TestSend templateId={template.templateId ?? ''} sms={sms} names={available} saved={body === original} />
    </div>
  );
}

/** One message to the editor's own phone or mailbox, with sample values; nothing is recorded in the outbox. */
function TestSend({ templateId, sms, names, saved }: { templateId: string; sms: boolean; names: string[]; saved: boolean }) {
  const [to, setTo] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<TestSendResult | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  async function send() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await sendTestMessage(templateId, to.trim(), samplesFor(names)));
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2 border-t border-border pt-3">
      <p className="text-xs font-medium text-label">Send me a test{saved ? '' : ' (of the saved wording — save first to test your changes)'}</p>
      <div className="flex flex-wrap gap-2">
        <Input className="w-full sm:w-64" aria-label={sms ? 'Your phone number' : 'Your email address'}
          placeholder={sms ? '+255712345678' : 'you@example.co.tz'} value={to} onChange={(e) => setTo(e.target.value)} />
        <Button size="sm" variant="outline" disabled={busy || to.trim() === ''} onClick={() => void send()}>
          {busy ? 'Sending…' : 'Send test'}
        </Button>
      </div>
      {error && <InlineError error={error} />}
      {result && (
        <p role="status" className={cn('rounded-md px-3 py-2 text-xs',
          result.outcome === 'SENT' ? 'bg-status-success-bg text-status-success-fg'
            : result.outcome === 'NOT_SENT' ? 'bg-status-warning-bg text-status-warning-fg' : 'bg-status-danger-bg text-status-danger-fg')}>
          {result.outcome === 'SENT' ? 'Sent. Check your phone or mailbox.'
            : result.outcome === 'NOT_SENT' ? `Not sent: ${result.detail ?? 'the channel is switched off'}.`
              : `Failed: ${result.detail ?? 'unknown reason'}.`}
        </p>
      )}
    </div>
  );
}

/** `policyNumber` reads as "Policy number". */
function humanise(name: string): string {
  const words = name.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
