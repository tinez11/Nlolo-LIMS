import { zodResolver } from '@hookform/resolvers/zod';
import { Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { RegulatoryReturnView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, type Column } from '@/components/DataTable';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad, isEmpty } from '@/store/createResourceSlice';
import { useRegreportingStore } from '@/store/regreportingStore';
import {
  blankGenerateReturnForm,
  generateReturnFormSchema,
  toApiRequest,
  type GenerateReturnFormValues,
} from './generateReturnForm';
import { RegulatoryReturnDrawer } from './RegulatoryReturnDrawer';

/**
 * `POST /regulatory-returns` + `GET` -- fully built and staff-reachable
 * (FINANCE_OFFICER/ADMIN) since M10, but with zero staff UI until this
 * staff-portal CRUD audit found the gap. `returnType` is free text, not a
 * dropdown: the return catalog is DATA (seeded `return_definition` rows),
 * not code, so there is no closed set to back a picker with --
 * `QUARTERLY_PRUDENTIAL` is the one type seeded in this tenant today.
 */
export function RegulatoryReturnsPage() {
  const list = useRegreportingStore((s) => s.list);
  const loadList = useRegreportingStore((s) => s.loadList);
  const [creatingOpen, setCreatingOpen] = useState(false);
  const [previewing, setPreviewing] = useState<string | null>(null);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  const columns: Column<RegulatoryReturnView>[] = [
    {
      key: 'returnType',
      header: 'Return type',
      render: (r) => <span className="font-medium">{r.returnType}</span>,
    },
    { key: 'period', header: 'Period', secondary: true, render: (r) => r.period },
    {
      key: 'status',
      header: 'Status',
      render: (r) => <StatusBadge kind="regulatoryReturn" value={r.status} />,
    },
    {
      key: 'generatedAt',
      header: 'Generated',
      align: 'right',
      secondary: true,
      render: (r) => formatInstant(r.generatedAt),
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;
    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={() => void loadList()} />;
    }
    if (isEmpty(list)) {
      return (
        <EmptyState
          title="No regulatory returns"
          description="Nothing has been generated for this tenant yet."
        />
      );
    }
    return (
      <DataTable
        columns={columns}
        rows={list.data ?? []}
        rowKey={(r) => r.returnId}
        onRowActivate={(r) => setPreviewing(r.returnId)}
        isRowSelected={(r) => r.returnId === previewing}
        caption="Regulatory returns"
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Regulatory returns"
        description="TIRA prudential returns. Regenerating an existing (return type, period) pair replaces its lines."
        actions={
          !creatingOpen && (
            <Button size="sm" variant="primary" onClick={() => setCreatingOpen(true)}>
              <Plus />
              Generate return
            </Button>
          )
        }
      />

      <div className="px-6 pb-6 space-y-4">
        {creatingOpen && (
          <GenerateReturnForm
            onDone={(returnId) => {
              setCreatingOpen(false);
              if (returnId) setPreviewing(returnId);
            }}
          />
        )}
        <div className="rounded-lg border border-border bg-surface">{renderBody()}</div>
      </div>

      <RegulatoryReturnDrawer returnId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}

function GenerateReturnForm({ onDone }: { onDone: (returnId?: string) => void }) {
  const generateReturn = useRegreportingStore((s) => s.generateReturn);
  const resetGenerateReturn = useRegreportingStore((s) => s.resetGenerateReturn);
  const generating = useRegreportingStore((s) => s.generating);

  useEffect(() => {
    resetGenerateReturn();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<GenerateReturnFormValues>({
    resolver: zodResolver(generateReturnFormSchema),
    defaultValues: blankGenerateReturnForm(),
  });

  async function onSubmit(values: GenerateReturnFormValues) {
    await generateReturn(toApiRequest(values));
    const result = useRegreportingStore.getState().generating;
    if (result.status === 'success') onDone(result.data?.returnId);
  }

  return (
    <form
      className="space-y-2 rounded-lg border border-border bg-surface p-3"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <div className="flex items-end gap-2">
        <label className="block">
          <span className="mb-1 block text-[11px] font-medium text-muted-foreground">
            Return type
          </span>
          <input
            className="h-8 w-52 rounded-md border border-input bg-surface px-2 text-xs"
            placeholder="QUARTERLY_PRUDENTIAL"
            {...register('returnType')}
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Period</span>
          <input
            className="h-8 w-32 rounded-md border border-input bg-surface px-2 text-xs"
            placeholder="2026-Q1"
            {...register('period')}
          />
        </label>
      </div>
      {errors.returnType?.message && (
        <p className="text-[11px] text-status-danger-fg">{errors.returnType.message}</p>
      )}
      {errors.period?.message && (
        <p className="text-[11px] text-status-danger-fg">{errors.period.message}</p>
      )}

      {generating.status === 'error' && generating.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {generating.error.detail ?? generating.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" disabled={generating.status === 'loading'}>
          {generating.status === 'loading' ? 'Generating…' : 'Generate'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={() => onDone()}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
