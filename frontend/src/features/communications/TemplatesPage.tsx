import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import type { NotificationTemplateView } from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useCommunicationsStore } from '@/store/communicationsStore';
import { humanizeTemplateKey } from './templateKeys';

/**
 * What this platform says to customers, and the one screen that can change it.
 *
 * <p>Without this, correcting a typo in a customer's SMS means writing a database migration and
 * deploying it. The wording of an operational message is a business decision, and the people who
 * own it are not the people who can deploy.
 *
 * <p>There is no "new template" and no delete, deliberately: the set of keys is defined by the
 * code that sends them. A key nothing sends is dead text, and a listener whose key was deleted
 * fails every send with nothing an operator can do about it. This screen changes wording.
 */
export function TemplatesPage() {
  const auth = useAuth();
  const isAdmin = readIdentity(auth.user?.access_token).roles.includes('ADMIN');

  const templates = useCommunicationsStore((s) => s.templates);
  const loadTemplates = useCommunicationsStore((s) => s.loadTemplates);

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  if (isInitialLoad(templates)) return <LoadingBlock label="Loading templates…" />;

  if (templates.status === 'error' && templates.error && templates.data === null) {
    return <ErrorPanel error={templates.error} onRetry={() => void loadTemplates()} />;
  }

  const rows = templates.data ?? [];
  if (rows.length === 0) {
    return (
      <>
        <PageHeader title="Message templates" />
        <EmptyState
          title="No templates for this tenant"
          description="Templates are seeded per tenant. Until they exist, every notification this platform tries to send will be recorded as failed."
        />
      </>
    );
  }

  // Grouped by message rather than listed flat: one message's SMS and email, Swahili and English,
  // are variants of the same thing and get read together when somebody rewrites it.
  const byKey = new Map<string, NotificationTemplateView[]>();
  for (const template of rows) {
    const key = template.templateKey ?? 'UNKNOWN';
    byKey.set(key, [...(byKey.get(key) ?? []), template]);
  }

  return (
    <>
      <PageHeader
        title="Message templates"
        description="The exact words customers receive. Editing one changes every message sent from then on."
      />
      {!isAdmin && (
        <p className="px-4 pb-3 text-xs text-muted-foreground">
          You can read every template here. Changing one needs an administrator — an edit reaches
          every customer who gets that message from then on.
        </p>
      )}
      <div className="space-y-4 px-4 pb-6">
        {[...byKey.entries()].map(([key, variants]) => (
          <Panel key={key} title={humanizeTemplateKey(key)} subtitle={key}>
            <div className="space-y-3 px-4 pb-4">
              {variants.map((template) => (
                <TemplateRow key={template.templateId} template={template} canEdit={isAdmin} />
              ))}
            </div>
          </Panel>
        ))}
      </div>
    </>
  );
}

function TemplateRow({ template, canEdit }: { template: NotificationTemplateView; canEdit: boolean }) {
  const [editing, setEditing] = useState(false);
  const [body, setBody] = useState(template.bodyTemplate ?? '');
  const reword = useCommunicationsStore((s) => s.reword);
  const rewording = useCommunicationsStore((s) => s.rewording);

  const busy = rewording.status === 'loading';

  async function save() {
    await reword(template.templateId ?? '', body);
    // Only leave edit mode on success. Closing the form on a 422 would throw away the text the
    // operator wrote along with the message telling them why it was refused.
    if (useCommunicationsStore.getState().rewording.status === 'success') {
      setEditing(false);
    }
  }

  return (
    <div className="rounded-md border border-border p-3">
      <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
        <span className="rounded bg-status-neutral-bg px-1.5 py-0.5 font-medium">{template.channel}</span>
        <span className="rounded bg-status-neutral-bg px-1.5 py-0.5 font-medium">{template.language}</span>
        {/* The tokens this body declares. Shown because an editor who does not know
            {'{{expiryDate}}'} exists will delete it, and a reminder that lost its deadline still
            sends — it just stops saying the thing it was for. */}
        {(template.placeholders ?? []).map((name) => (
          <code key={name} className="rounded bg-status-pending-bg px-1 py-0.5 text-[11px]">
            {`{{${name}}}`}
          </code>
        ))}
      </div>

      {editing ? (
        <div className="space-y-2">
          <textarea
            aria-label={`${template.templateKey} ${template.channel} ${template.language} body`}
            className="w-full rounded border border-border bg-background p-2 text-sm"
            rows={4}
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
          {rewording.status === 'error' && rewording.error && (
            <p role="alert" className="text-[11px] text-status-danger-fg">
              {rewording.error.detail ?? 'Could not save this wording.'}
            </p>
          )}
          <div className="flex gap-2">
            <Button size="sm" variant="primary" disabled={busy} onClick={() => void save()}>
              {busy ? 'Saving…' : 'Save wording'}
            </Button>
            <Button
              size="sm"
              disabled={busy}
              onClick={() => {
                setBody(template.bodyTemplate ?? '');
                setEditing(false);
              }}
            >
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <div className="flex items-start justify-between gap-3">
          <p className="text-sm">{template.bodyTemplate}</p>
          {canEdit && (
            <Button size="sm" onClick={() => setEditing(true)}>
              Edit
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
