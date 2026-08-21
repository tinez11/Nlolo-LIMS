import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Policy list for the signed-in customer. `policyholderPartyId` is deliberately NOT forwarded even
 * if a client sends one: the backend force-scopes it from the token's party_id claim (M12 Task 1),
 * and passing it through would be a bug even when the value is correct.
 */
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  try {
    const data = await callBackend<unknown>('/policies', {
      searchParams: {
        page: params.get('page') ?? 0,
        pageSize: params.get('pageSize') ?? 20,
        status: params.get('status') ?? undefined,
      },
    });
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
