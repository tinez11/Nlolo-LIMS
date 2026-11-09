import { useState } from 'react';
import { saveBranch, type Branch } from '@/api/organisation';
import { DataTable, type Column } from '@/components/DataTable';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Textarea } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';

const EMPTY: Branch = { code: '', name: '', address: null, phone: null, email: null, manager: null, active: true, headOffice: false };

/**
 * The company's branches (2026-11-09): its own register, replacing the shared list every company used to pick from. A
 * closed branch stays on its old policies and leaves the pickers; the head office -- set on the Company tab -- is the
 * one documents carry and cannot be closed.
 */
export function BranchesTab({ branches, onSaved }: { branches: Branch[]; onSaved: () => void }) {
  const [editing, setEditing] = useState<Branch | null>(null);
  const [isNew, setIsNew] = useState(false);

  const columns: Column<Branch>[] = [
    { key: 'code', header: 'Code', nowrap: true, render: (b) => <span className="font-medium">{b.code}</span> },
    {
      key: 'name',
      header: 'Branch',
      render: (b) => (
        <span className="flex flex-col gap-0.5">
          <span>{b.name}{b.headOffice && <span className="ml-2 rounded-full bg-control px-2 py-0.5 text-xs text-muted-foreground">Head office</span>}</span>
          {b.address && <span className="text-xs text-muted-foreground">{b.address}</span>}
        </span>
      ),
    },
    { key: 'contact', header: 'Contact', secondary: true, render: (b) => [b.phone, b.email].filter(Boolean).join(' · ') || '—' },
    { key: 'manager', header: 'Manager', secondary: true, render: (b) => b.manager ?? '—' },
    { key: 'active', header: 'Status', render: (b) => (b.active ? 'Open' : 'Closed') },
    {
      key: 'act',
      header: <span className="sr-only">Change</span>,
      align: 'right',
      render: (b) => (
        <Button size="sm" variant="ghost" aria-label={`Change ${b.name}`} onClick={() => { setIsNew(false); setEditing(b); }}>
          Change
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-5 pt-5">
      <Panel title="Branches" subtitle="Where sales are made and staff and agents are based."
        actions={<Button size="sm" variant="secondary" onClick={() => { setIsNew(true); setEditing(EMPTY); }}>Add a branch</Button>}>
        <DataTable columns={columns} rows={branches} rowKey={(b) => b.code} caption="Branches" />
      </Panel>
      {editing && (
        <Panel title={isNew ? 'Add a branch' : `Change ${editing.name}`}>
          <BranchForm key={editing.code || 'new'} initial={editing} isNew={isNew} onCancel={() => setEditing(null)}
            onSaved={() => { setEditing(null); onSaved(); }} />
        </Panel>
      )}
    </div>
  );
}

function BranchForm({ initial, isNew, onCancel, onSaved }: {
  initial: Branch;
  isNew: boolean;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [draft, setDraft] = useState<Branch>(initial);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const set = (field: keyof Branch) => (value: string) => setDraft((d) => ({ ...d, [field]: value === '' ? null : value }));

  async function submit() {
    setPending(true);
    setError(null);
    try {
      await saveBranch({ ...draft, code: draft.code.trim().toUpperCase(), name: (draft.name ?? '').trim() });
      onSaved();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="space-y-4 px-4 pb-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField required label="Code" hint={isNew ? '2 to 20 capital letters or digits, such as MWZ. Fixed once saved.' : 'Fixed once saved.'}>
          <Input value={draft.code} disabled={!isNew} maxLength={20}
            onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} />
        </FormField>
        <FormField required label="Name">
          <Input value={draft.name ?? ''} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        </FormField>
        <FormField label="Address" className="sm:col-span-2">
          <Textarea rows={2} value={draft.address ?? ''} onChange={(e) => set('address')(e.target.value)} />
        </FormField>
        <FormField label="Phone">
          <Input value={draft.phone ?? ''} onChange={(e) => set('phone')(e.target.value)} />
        </FormField>
        <FormField label="Email">
          <Input type="email" value={draft.email ?? ''} onChange={(e) => set('email')(e.target.value)} />
        </FormField>
        <FormField label="Manager">
          <Input value={draft.manager ?? ''} onChange={(e) => set('manager')(e.target.value)} />
        </FormField>
      </div>
      <CheckboxField label="Open: offered when a sale, an agent or a staff member is placed" checked={draft.active}
        disabled={draft.headOffice} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
      {draft.headOffice && <p className="text-xs text-muted-foreground">The head office cannot be closed.</p>}
      {error && <InlineError lead="The branch was not saved" error={error} />}
      <div className="flex gap-2">
        <Button variant="primary" pending={pending} onClick={() => void submit()}>{isNew ? 'Add the branch' : 'Save'}</Button>
        <Button variant="ghost" disabled={pending} onClick={onCancel}>Cancel</Button>
      </div>
    </div>
  );
}
