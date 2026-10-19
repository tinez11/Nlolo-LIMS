import { useEffect, useState } from 'react';
import { downloadSchemeQuotation } from '@/api/documents';
import { quoteGroupFuneral } from '@/api/groupFuneral';
import type { GroupFuneralQuoteRequest, GroupFuneralQuoteView } from '@/api/types';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatMoney } from '@/lib/money';
import { DownloadQuotationButton } from '@/features/underwriting/DownloadQuotationButton';
import type { FuneralRoleName } from '@/features/products/funeralSchema';
import { toLives, type FamilyRow } from './groupFuneral';

/** One member's family, in the shape both the quote and the scheme quotation take. */
interface SchemeFamily {
  memberReference: string;
  lives: { role: FuneralRoleName; name: string; dateOfBirth: string | null; student: boolean }[];
}

/** Each member's family as the quote takes it: the lives toLives sends the proposal, grouped by member number. */
function toFamilies(families: FamilyRow[]): SchemeFamily[] {
  const byMember = new Map<string, SchemeFamily>();
  for (const life of toLives(families)) {
    const reference = life.memberReference ?? '';
    const family = byMember.get(reference) ?? { memberReference: reference, lives: [] };
    family.lives.push({
      role: life.role as FuneralRoleName,
      name: life.fullName ?? '',
      dateOfBirth: life.dateOfBirth ?? null,
      student: life.student ?? false,
    });
    byMember.set(reference, family);
  }
  return [...byMember.values()];
}

type SchemeQuote =
  | { state: 'incomplete' }
  | { state: 'loading' }
  | { state: 'quoted'; quote: GroupFuneralQuoteView }
  | { state: 'refused'; error: ApiError };

/**
 * The scheme priced and checked on the server as it is written (2026-10-19). Debounced, because it follows every
 * keystroke in a name or a date of birth; an answer for other inputs is no answer for these.
 */
function useSchemeQuote(productId: string, versionId: string, planCode: string, commencement: string,
                        families: FamilyRow[]): SchemeQuote {
  const ready = Boolean(productId && versionId && planCode);
  const body = JSON.stringify({ planCode, asOf: commencement || null, families: toFamilies(families) });
  const key = ready ? `${productId}|${versionId}|${body}` : '';
  const [loaded, setLoaded] = useState<{ key: string; quote?: GroupFuneralQuoteView; error?: ApiError } | null>(null);

  useEffect(() => {
    if (!key) return undefined;
    let live = true;
    const timer = setTimeout(() => {
      quoteGroupFuneral(productId, versionId, JSON.parse(body) as GroupFuneralQuoteRequest).then(
        (quote) => live && setLoaded({ key, quote }),
        (error: unknown) => live && setLoaded({ key, error: toApiError(error) }),
      );
    }, 400);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [key, productId, versionId, body]);

  if (!ready) return { state: 'incomplete' };
  if (!loaded || loaded.key !== key) return { state: 'loading' };
  if (loaded.error) return { state: 'refused', error: loaded.error };
  return { state: 'quoted', quote: loaded.quote as GroupFuneralQuoteView };
}

/**
 * The Quotation panel on "Propose a group funeral scheme" (2026-10-19), as the individual open-case form has one.
 *
 * <p><b>The bill is the server's.</b> It used to be "members x rate" multiplied on this page; it is now the product
 * module's group funeral quote, the figure issuance bills. <b>A family the plan will not take is named on its member
 * as it is typed</b> -- by the same role rules the proposal applies -- instead of the whole scheme being refused when
 * it is submitted. Nothing is created by looking at it.
 */
export function SchemeQuotation({
  productId,
  versionId,
  planCode,
  commencement,
  families,
  associationPartyId,
  currency,
}: {
  productId: string;
  versionId: string;
  planCode: string;
  commencement: string;
  families: FamilyRow[];
  associationPartyId: string;
  currency: string;
}) {
  const result = useSchemeQuote(productId, versionId, planCode, commencement, families);
  const money = (n: number | null | undefined) => (n == null ? '—' : formatMoney({ amount: String(n), currencyCode: currency }));

  let body;
  if (result.state === 'incomplete') {
    body = <p className="px-4 pb-4 text-xs text-muted-foreground">Choose the product and the plan to price the scheme.</p>;
  } else if (result.state === 'loading') {
    body = <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>;
  } else if (result.state === 'refused') {
    body = <div className="px-4 pb-4"><InlineError error={result.error} /></div>;
  } else {
    const q = result.quote;
    const per = q.period === 'YEARLY' ? 'a year' : 'a month';
    body = (
      <div className="px-4 pb-2">
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-sm">
            <caption className="sr-only">The scheme's families</caption>
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="p-2">Member no.</th>
                <th className="p-2">Main member</th>
                <th className="p-2">Lives</th>
                <th className="p-2 text-right">Family cover</th>
              </tr>
            </thead>
            <tbody>
              {q.families.map((f, i) => (
                <tr key={i} className="border-t border-border align-top">
                  <td className="p-2">{f.reference || '—'}</td>
                  <td className="p-2">
                    {f.mainMember || '—'}
                    {f.problems.length > 0 && (
                      <ul className="mt-1 list-disc pl-4 text-xs text-status-danger-fg" aria-label={`Problems for member ${f.reference ?? ''}`}>
                        {f.problems.map((p) => <li key={p}>{p}</li>)}
                      </ul>
                    )}
                  </td>
                  <td className="p-2">{f.lives}</td>
                  <td className="p-2 text-right whitespace-nowrap">{money(f.cover)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <dl className="mt-2 divide-y divide-border text-sm">
          <div className="flex justify-between gap-4 py-1.5"><dt>Members</dt><dd>{q.members}</dd></div>
          <div className="flex justify-between gap-4 py-1.5">
            <dt>Group rate</dt>
            <dd className="whitespace-nowrap">{money(q.rate)} <span className="text-xs text-muted-foreground">per member {per}</span></dd>
          </div>
          <div className="flex justify-between gap-4 py-1.5 font-medium">
            <dt>The bill</dt>
            <dd className="whitespace-nowrap" aria-label="The bill">{money(q.bill)} <span className="text-xs font-normal text-muted-foreground">{per}</span></dd>
          </div>
          <div className="flex justify-between gap-4 py-1.5">
            <dt>Total cover</dt>
            <dd className="whitespace-nowrap">{q.totalCover == null ? 'Not until every family can join' : money(q.totalCover)}</dd>
          </div>
        </dl>
      </div>
    );
  }

  // A quotation the association signs is only for a scheme that can actually be proposed.
  const printable = result.state === 'quoted' && result.quote.problems.length === 0 && result.quote.members > 0
    && Boolean(associationPartyId);

  return (
    <Panel
      title="Quotation"
      subtitle="What the association would be billed on the plan above. Nothing is created by looking at it."
    >
      {body}
      <DownloadQuotationButton
        download={printable ? () => downloadSchemeQuotation({
          productId,
          productVersionId: versionId,
          associationPartyId,
          planCode,
          commencement: commencement || null,
          families: toFamilies(families),
        }) : null}
        fileName={`scheme-quotation-${versionId.slice(0, 8)}.pdf`}
        description="The bill and each family's cover, for the association to sign and keep."
      />
    </Panel>
  );
}
