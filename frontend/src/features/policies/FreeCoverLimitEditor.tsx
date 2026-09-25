import { useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { canUnderwriteGroupSchemes, readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { AMOUNT_PATTERN, formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectScheme, usePolicyStore } from '@/store/policyStore';

/**
 * Moving a live scheme's free cover limit.
 *
 * <p><b>Why a scheme needed any edit at all.</b> A limit is a number somebody types once, and a
 * wrong one is not a cosmetic error: it insures every borrower on the book for a fraction of their
 * loan and opens an underwriting case for each of them. One real scheme was set up at 1,000,000
 * against loans of 5,000,000 — every member capped at a fifth, three cases nobody wanted — on a
 * product whose agreed limit is 600,000,000 precisely so the cap never fires. There was no
 * amendment path anywhere, so the only remedy was a second scheme and a lender asked to send their
 * file again.
 *
 * <p><b>It is the only term here, and the others are not an oversight.</b> The interest method,
 * the repayment frequency and the premium rate are write-once in the domain because members were
 * valued and CHARGED against them — restating those rewrites history, and a rate change is a new
 * scheme. This one only decides how much of a benefit is covered today, and cover is
 * effective-dated, so moving it writes new rows from today.
 *
 * <p>Underwriters only, matching the endpoint and matching issuance: deciding how much of every
 * borrower's loan is insured is the same act as setting it in the first place.
 */
export function FreeCoverLimitEditor({ policyNumber }: { policyNumber: string }) {
  const canUnderwrite = canUnderwriteGroupSchemes(readIdentity(useAuth().user?.access_token));
  const scheme = usePolicyStore(selectScheme(policyNumber));
  const amend = usePolicyStore((s) => s.amendFreeCoverLimit);

  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');

  if (!canUnderwrite || isInitialLoad(scheme)) return null;

  const busy = scheme.status === 'loading';
  // Blank is a real answer — "this scheme has no limit" — so it is only the SHAPE that is
  // checked here. Zero is refused by the server rather than reinterpreted, because a limit of
  // zero would send every borrower to underwriting.
  const malformed = amount.trim() !== '' && !AMOUNT_PATTERN.test(amount.trim());
  const blocked = malformed || reason.trim() === '';

  if (!open) {
    return (
      <div className="border-t border-border px-4 py-2">
        <Button
          size="sm"
          variant="ghost"
          className="-ml-2"
          onClick={() => {
            setAmount(scheme.data?.fcl?.amount ?? '');
            setReason('');
            setOpen(true);
          }}
        >
          Change the free cover limit
        </Button>
        {scheme.status === 'error' && scheme.error && (
          <p role="alert" className="mt-1 text-[11px] text-status-danger-fg">
            {scheme.error.detail ?? scheme.error.title}
          </p>
        )}
      </div>
    );
  }

  return (
    <div className="border-t border-border px-4 py-3">
      <ConfirmAct
        heading="Change the free cover limit?"
        consequence={
          <>
            Every borrower on this scheme is revalued against the new limit from today. Those
            capped by the old one are covered for more, and stop waiting on medical evidence for an
            excess that no longer exists.
          </>
        }
        // True, and checked: the endpoint refuses any amendment that would reduce live cover.
        reversal="Cover already in force is never reduced — a limit that would uninsure somebody is refused outright. Yesterday's cover stays what it was, because each change is a new effective-dated row."
        confirmLabel="Change the limit"
        busy={busy}
        confirmDisabled={blocked}
        onConfirm={() => {
          void amend(policyNumber, amount.trim() === '' ? null : amount.trim(), reason.trim());
          setOpen(false);
        }}
        onCancel={() => setOpen(false)}
      >
        <div className="grid gap-2 sm:grid-cols-2">
          <label className="block text-xs">
            <span className="font-medium">New limit</span>
            <Input
              className="mt-1"
              value={amount}
              placeholder="600000000.00"
              onChange={(e) => setAmount(e.target.value)}
            />
            <span className="mt-1 block text-[11px] text-muted-foreground">
              {amount.trim() === ''
                ? 'Blank means this scheme has no limit at all — every borrower covered in full.'
                : `Currently ${scheme.data?.fcl ? formatMoney(scheme.data.fcl) : 'no limit'}.`}
            </span>
          </label>
          <label className="block text-xs">
            <span className="font-medium">Why</span>
            <Input
              className="mt-1"
              value={reason}
              placeholder="Typed wrong at set-up; agreed figure is 600,000,000"
              onChange={(e) => setReason(e.target.value)}
            />
            <span className="mt-1 block text-[11px] text-muted-foreground">
              Recorded against the scheme. A limit is a term agreed with a lender.
            </span>
          </label>
        </div>
      </ConfirmAct>
      {malformed && (
        <p className="mt-1 text-[11px] text-status-danger-fg">
          An amount like 600000000.00, or blank for no limit.
        </p>
      )}
      {!malformed && reason.trim() === '' && (
        <p className="mt-1 text-[11px] text-status-danger-fg">
          Say why. The change is recorded against the scheme.
        </p>
      )}
    </div>
  );
}
