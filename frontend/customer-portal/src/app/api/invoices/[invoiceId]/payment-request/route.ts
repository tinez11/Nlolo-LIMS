import { NextRequest, NextResponse } from 'next/server';
import { randomUUID } from 'node:crypto';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Billing's payment-request enforces Idempotency-Key for real (a DB-backed registry), unlike the
 * loan endpoints -- so no Redis claim is needed here. The client supplies the key so a user-driven
 * retry of the SAME attempt deduplicates; a missing one is generated rather than sent blank, which
 * the backend rejects with a 400.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ invoiceId: string }> },
) {
  const { invoiceId } = await params;
  const body = (await request.json()) as { payerRef: string; idempotencyKey?: string };
  try {
    await callBackend(`/invoices/${encodeURIComponent(invoiceId)}/payment-request`, {
      method: 'POST',
      body: { payerRef: body.payerRef },
      headers: { 'Idempotency-Key': body.idempotencyKey ?? randomUUID() },
    });
    return new NextResponse(null, { status: 202 });
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
