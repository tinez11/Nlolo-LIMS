'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useSubmitGuard } from '@/hooks/use-submit-guard';
import { mapApiError, type ApiProblem } from '@/lib/problem';

type ClaimTypeValue = 'DEATH' | 'DISABILITY' | 'CRITICAL_ILLNESS' | 'MATURITY';

class RegisterClaimError extends Error {
  constructor(readonly problem: ApiProblem | null, readonly status: number) {
    super(problem?.title ?? `Request failed with status ${status}`);
    this.name = 'RegisterClaimError';
  }
}

/**
 * Registration form for a new claim. `claimantPartyId` appears nowhere in this file: the Route
 * Handler at `/api/claims` derives it itself, server-side, from the caller's own verified token
 * (see `resolveOwnPartyId` in `lib/backend.ts`) -- the browser never supplies, and could not
 * correctly guess, that value.
 *
 * Layer 1 only (`useSubmitGuard`): `POST /claims` is genuinely idempotent server-side (a real
 * `Idempotency-Key` dedup registry), unlike the two loan endpoints Task 12 guards with a full
 * two-layer (useRef + Redis) claim, so no Redis claim is used here. The Idempotency-Key is
 * generated once per mount of this form, not per keystroke or per click, so a retry of the same
 * submit attempt (e.g. re-clicking after a transient network failure) reuses the same key.
 */
export default function NewClaimPage() {
  const router = useRouter();
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [error, setError] = useState<string | null>(null);

  const [policyNumber, setPolicyNumber] = useState('');
  const [claimType, setClaimType] = useState<ClaimTypeValue>('DEATH');
  const [dateOfEvent, setDateOfEvent] = useState('');

  const [causeOfDeath, setCauseOfDeath] = useState('');
  const [placeOfDeath, setPlaceOfDeath] = useState('');
  const [dateOfDeath, setDateOfDeath] = useState('');
  const [attendingPhysician, setAttendingPhysician] = useState('');

  const [disabilityType, setDisabilityType] = useState('');
  const [onsetDate, setOnsetDate] = useState('');
  const [permanent, setPermanent] = useState(false);
  const [impairmentPercent, setImpairmentPercent] = useState('');

  const [diagnosis, setDiagnosis] = useState('');
  const [diagnosisDate, setDiagnosisDate] = useState('');
  const [icdCode, setIcdCode] = useState('');

  const [maturityDate, setMaturityDate] = useState('');

  function buildDetails(): unknown {
    switch (claimType) {
      case 'DEATH':
        return { claimType, causeOfDeath, placeOfDeath, dateOfDeath, attendingPhysician };
      case 'DISABILITY':
        return { claimType, disabilityType, onsetDate, permanent, impairmentPercent };
      case 'CRITICAL_ILLNESS':
        return { claimType, diagnosis, diagnosisDate, icdCode };
      case 'MATURITY':
        return { claimType, maturityDate };
    }
  }

  const { submit, isSubmitting } = useSubmitGuard(async () => {
    setError(null);
    try {
      const response = await fetch('/api/claims', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          policyNumber, claimType, dateOfEvent, details: buildDetails(), idempotencyKey,
        }),
      });
      const body = await response.json().catch(() => null);
      if (!response.ok) {
        throw new RegisterClaimError(body as ApiProblem | null, response.status);
      }
      const claim = body as { claimId: string };
      router.push(`/claims/${claim.claimId}`);
    } catch (rejection) {
      setError(
        rejection instanceof RegisterClaimError
          ? mapApiError(rejection.problem, rejection.status)
          : mapApiError(null),
      );
    }
  });

  return (
    <div className="max-w-xl space-y-4">
      <h1 className="text-xl font-semibold">Register a claim</h1>

      <form onSubmit={(event) => { event.preventDefault(); void submit(); }} className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="policyNumber">Policy number</Label>
          <Input
            id="policyNumber"
            value={policyNumber}
            onChange={(event) => setPolicyNumber(event.target.value)}
            required
          />
        </div>

        <div className="space-y-1">
          <Label htmlFor="claimType">Claim type</Label>
          <select
            id="claimType"
            value={claimType}
            onChange={(event) => setClaimType(event.target.value as ClaimTypeValue)}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm"
          >
            <option value="DEATH">Death</option>
            <option value="DISABILITY">Disability</option>
            <option value="CRITICAL_ILLNESS">Critical illness</option>
            <option value="MATURITY">Maturity</option>
          </select>
        </div>

        <div className="space-y-1">
          <Label htmlFor="dateOfEvent">Date of event</Label>
          <Input
            id="dateOfEvent"
            type="date"
            value={dateOfEvent}
            onChange={(event) => setDateOfEvent(event.target.value)}
            required
          />
        </div>

        {claimType === 'DEATH' && (
          <div className="space-y-3 rounded-lg border border-border p-3">
            <div className="space-y-1">
              <Label htmlFor="causeOfDeath">Cause of death</Label>
              <Input id="causeOfDeath" value={causeOfDeath} onChange={(e) => setCauseOfDeath(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="placeOfDeath">Place of death</Label>
              <Input id="placeOfDeath" value={placeOfDeath} onChange={(e) => setPlaceOfDeath(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="dateOfDeath">Date of death</Label>
              <Input id="dateOfDeath" type="date" value={dateOfDeath} onChange={(e) => setDateOfDeath(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="attendingPhysician">Attending physician</Label>
              <Input id="attendingPhysician" value={attendingPhysician} onChange={(e) => setAttendingPhysician(e.target.value)} required />
            </div>
          </div>
        )}

        {claimType === 'DISABILITY' && (
          <div className="space-y-3 rounded-lg border border-border p-3">
            <div className="space-y-1">
              <Label htmlFor="disabilityType">Disability type</Label>
              <Input id="disabilityType" value={disabilityType} onChange={(e) => setDisabilityType(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="onsetDate">Onset date</Label>
              <Input id="onsetDate" type="date" value={onsetDate} onChange={(e) => setOnsetDate(e.target.value)} required />
            </div>
            <div className="flex items-center gap-2">
              <input
                id="permanent"
                type="checkbox"
                checked={permanent}
                onChange={(e) => setPermanent(e.target.checked)}
              />
              <Label htmlFor="permanent">Permanent</Label>
            </div>
            <div className="space-y-1">
              <Label htmlFor="impairmentPercent">Impairment percent</Label>
              <Input id="impairmentPercent" value={impairmentPercent} onChange={(e) => setImpairmentPercent(e.target.value)} required />
            </div>
          </div>
        )}

        {claimType === 'CRITICAL_ILLNESS' && (
          <div className="space-y-3 rounded-lg border border-border p-3">
            <div className="space-y-1">
              <Label htmlFor="diagnosis">Diagnosis</Label>
              <Input id="diagnosis" value={diagnosis} onChange={(e) => setDiagnosis(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="diagnosisDate">Diagnosis date</Label>
              <Input id="diagnosisDate" type="date" value={diagnosisDate} onChange={(e) => setDiagnosisDate(e.target.value)} required />
            </div>
            <div className="space-y-1">
              <Label htmlFor="icdCode">ICD code</Label>
              <Input id="icdCode" value={icdCode} onChange={(e) => setIcdCode(e.target.value)} required />
            </div>
          </div>
        )}

        {claimType === 'MATURITY' && (
          <div className="space-y-3 rounded-lg border border-border p-3">
            <div className="space-y-1">
              <Label htmlFor="maturityDate">Maturity date</Label>
              <Input id="maturityDate" type="date" value={maturityDate} onChange={(e) => setMaturityDate(e.target.value)} required />
            </div>
          </div>
        )}

        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}

        <Button type="submit" disabled={isSubmitting}>Register claim</Button>
      </form>
    </div>
  );
}
