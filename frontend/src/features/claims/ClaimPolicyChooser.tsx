import { Search } from 'lucide-react';
import { useEffect } from 'react';
import type { PolicyView } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ErrorPanel } from '@/components/states';
import { cn } from '@/lib/cn';
import { formatMoney } from '@/lib/money';
import {
  CAPACITY_HINTS,
  CAPACITY_LABELS,
  policyCapacities,
  willAcceptClaimToday,
} from '@/lib/policyCapacity';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaimantPolicies, usePolicyStore } from '@/store/policyStore';

/**
 * Pick the policy a claim is being filed against, from the policies the CLAIMANT is actually
 * connected to.
 *
 * Replaces a free-text `POL-XXXXXXXX` box, which required the clerk to already know the number
 * -- so the number came off a paper form, or off a second browser tab, and a typo produced a
 * 404 with no hint of what was meant.
 *
 * **A flat list of cards, not a searchable popover.** A claimant has one to five recorded
 * policies, and the whole value here is seeing them side by side with the capacity and status
 * that distinguish them. A combobox would hide exactly the comparison the clerk came to make.
 * The filter box only appears once the list is long enough to need one.
 *
 * **Nothing is disabled, deliberately.** A policy that is not currently in force WILL be
 * refused by `POST /claims` today, and that is a known-wrong rule (`isPolicyInForce` ignores
 * the `asOf` it accepts, so a valid claim for an event that preceded a lapse is rejected).
 * `claimGates` already treats the same fact as a SOFT gate on purpose, with the reasoning that
 * a lapsed policy must route a claim to investigation and never be auto-refused, because
 * refusing without proving the lapse notices were sent is how an insurer ends up in front of a
 * regulator. Greying the row out here would be that refusal, made earlier and more quietly, so
 * instead the row says plainly what will happen and names the remedy.
 */
export function ClaimPolicyChooser({
  claimantPartyId,
  value,
  onChange,
  filter,
  onFilterChange,
  onEnterManually,
}: {
  claimantPartyId: string;
  value: string;
  onChange: (policyNumber: string) => void;
  filter: string;
  onFilterChange: (next: string) => void;
  onEnterManually: () => void;
}) {
  const loadClaimantPolicies = usePolicyStore((s) => s.loadClaimantPolicies);
  const policies = usePolicyStore(selectClaimantPolicies(claimantPartyId, filter));

  useEffect(() => {
    if (!claimantPartyId) return;
    // Debounced for the same reason PartyPicker debounces its own search: the filter goes to
    // the server, so a request per keystroke would be a request per keystroke.
    const timer = setTimeout(() => void loadClaimantPolicies(claimantPartyId, filter), 250);
    return () => clearTimeout(timer);
  }, [claimantPartyId, filter, loadClaimantPolicies]);

  if (!claimantPartyId) {
    return (
      <div className="rounded-md border border-dashed border-border px-3 py-3">
        <p className="text-xs text-muted-foreground">
          Choose the claimant above and their policies are listed here.
        </p>
        {/* The escape hatch is offered HERE too, not only once a claimant is chosen. A clerk
            holding the policy number already -- off a paper claim form, off a phone call --
            should not have to identify the claimant first just to reach a text box. Requiring
            that would make the new order mandatory rather than merely the default. */}
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="-ml-2 mt-1"
          onClick={onEnterManually}
        >
          Enter a policy number instead
        </Button>
      </div>
    );
  }

  if (isInitialLoad(policies)) {
    return (
      <div className="space-y-2">
        {[0, 1].map((i) => (
          <div key={i} className="h-16 animate-pulse rounded-md bg-hover" />
        ))}
      </div>
    );
  }

  if (policies.status === 'error' && policies.error && policies.data === null) {
    return (
      <ErrorPanel
        error={policies.error}
        onRetry={() => void loadClaimantPolicies(claimantPartyId)}
      />
    );
  }

  // The server has already applied the filter, so these ARE the rows -- no second,
  // client-side pass that could only ever narrow the wrong hundred.
  const rows = policies.data?.items ?? [];
  const total = policies.data?.page.totalElements ?? rows.length;
  const truncated = total > rows.length;
  const filtering = filter.trim().length > 0;

  if (rows.length === 0 && !filtering) {
    return (
      <div className="rounded-md border border-border bg-surface px-3 py-3">
        {/* Says WHAT WAS SEARCHED, so "none" is a fact rather than a mystery. An empty list
            that does not explain its own scope reads as "this person has no policies", which
            is a stronger claim than the query actually made. */}
        <p className="text-xs text-muted-foreground">
          No policy on record names this client as its owner, as the insured life, or as a
          beneficiary.
        </p>
        <p className="mt-1.5 text-xs text-muted-foreground">
          A claim can still be filed — an executor or an assignee has no recorded connection,
          and the platform does not require one.
        </p>
        <Button type="button" size="sm" variant="ghost" className="-ml-2 mt-1" onClick={onEnterManually}>
          Enter a policy number instead
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {/* States the SCOPE of the list, once, above it. Without this the list is three policies
          with no stated basis, and a clerk cannot tell whether it is "all policies", "policies
          this person owns", or something else -- which decides whether an absence means
          anything. */}
      <p className="text-xs text-subtle-foreground">
        Policies this client owns, is insured under, or is named on
      </p>

      {/* Eight is where scanning stops being faster than typing. Below it a filter box is one
          more control between the clerk and the four cards they can already read -- but once
          the list is truncated the box is the ONLY way to reach the rest, so it must appear
          whenever it is truncated regardless of how many rows came back. */}
      {(rows.length > 8 || truncated || filtering) && (
        /* The icon is not decoration. This box sits directly under the "Policy" label, and
           without it a filled filter reads as the policy VALUE -- indistinguishable from the
           free-text field this chooser replaced, which is exactly the wrong thing to imply.
           The magnifier says "this narrows the list below" before the placeholder is gone. */
        <div className="relative">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-subtle-foreground"
          />
          <Input
            inputSize="sm"
            aria-label="Filter policies"
            placeholder="Filter by policy number"
            className="pl-7"
            value={filter}
            onChange={(e) => onFilterChange(e.target.value)}
          />
        </div>
      )}

      {truncated && (
        /* Disclosed, because the sort is `createdAt DESC` and the server caps the page at 100:
           what is missing is this claimant's OLDEST policies, which on a life book are the
           ones most likely to be claimed against. A list that quietly showed 100 of 214 would
           be answering a different question from the one it appears to answer. */
        <p className="text-xs text-status-warning-fg">
          Showing the {rows.length} most recent of {total}. The oldest are not listed — filter
          by policy number to find one.
        </p>
      )}

      <div role="radiogroup" aria-label="Policy to claim against" className="space-y-2">
        {rows.map((policy) => (
          <PolicyOption
            key={policy.policyNumber}
            policy={policy}
            claimantPartyId={claimantPartyId}
            selected={policy.policyNumber === value}
            onSelect={() => onChange(policy.policyNumber ?? '')}
          />
        ))}
      </div>

      {rows.length === 0 && filtering && (
        <p className="text-xs text-muted-foreground">
          No policy of this client&apos;s matches “{filter.trim()}”.
        </p>
      )}

      <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={onEnterManually}>
        Enter a different policy number
      </Button>
    </div>
  );
}

function PolicyOption({
  policy,
  claimantPartyId,
  selected,
  onSelect,
}: {
  policy: PolicyView;
  claimantPartyId: string;
  selected: boolean;
  onSelect: () => void;
}) {
  const capacities = policyCapacities(policy, claimantPartyId);
  const acceptable = willAcceptClaimToday(policy);

  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={cn(
        'w-full rounded-md border px-3 py-2.5 text-left transition-colors',
        selected
          ? 'border-border-strong bg-selected'
          : 'border-border bg-surface hover:bg-hover',
      )}
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="font-mono text-sm font-medium">{policy.policyNumber}</span>
        {policy.status && <StatusBadge kind="policy" value={policy.status} />}
        <span className="ml-auto text-sm">
          {policy.sumAssured ? formatMoney(policy.sumAssured) : '—'}
        </span>
      </div>

      {/* The capacity is the reason this list is worth showing. Every capacity, not a
          "primary" one: on a self-insured policy the claimant is both owner and insured life,
          and collapsing that to one label would hide which claim this can support. */}
      <p className="mt-1 text-xs text-muted-foreground">
        {capacities.length > 0 ? (
          // Just the joined hints. An earlier version appended "-- the same person owns it and
          // is insured under it" for the multi-capacity case, which restated the sentence it
          // was appended to: "owns this contract, and is the insured life on this contract"
          // already says exactly that.
          <>This client {capacities.map((c) => CAPACITY_HINTS[c]).join(', and ')}</>
        ) : (
          // Reachable: the manual-entry path can select a policy the claimant has no
          // recorded link to, and this component still has to describe it honestly.
          'No recorded connection between this client and this policy'
        )}
      </p>

      <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
        {capacities.map((c) => (
          <span
            key={c}
            className="rounded border border-border px-1.5 py-0.5 text-[11px] text-muted-foreground"
          >
            {CAPACITY_LABELS[c]}
          </span>
        ))}
      </div>

      {!acceptable && (
        /* Warned, never blocked -- see the component header. Naming the remedy matters: the
           backend's own comment says staff must reinstate the policy before the claim can be
           filed, and nothing else on screen would tell them that. */
        <p className="mt-1.5 text-[11px] text-status-warning-fg">
          Registration will be refused while the policy is{' '}
          {(policy.status ?? 'not in force').toLowerCase()} — it has to be reinstated first.
          Choose it anyway if this is the right contract; the rejection will say the same thing.
        </p>
      )}
    </button>
  );
}
