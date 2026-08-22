'use client';

import { useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

/**
 * App Router error boundary for every screen under the `(portal)` route group. Without this, a
 * single malformed value from the backend (e.g. one that trips `formatMoney`'s deliberate throw
 * on a malformed amount) crashes the whole page with Next's raw error screen instead of a
 * friendly message the customer can act on.
 */
export default function PortalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <Card className="max-w-md">
      <CardHeader>
        <CardTitle>Something went wrong</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <p className="text-muted-foreground">
          This page could not be displayed. Please try again, or contact support if the problem
          continues.
        </p>
        <Button type="button" variant="outline" onClick={reset}>
          Try again
        </Button>
      </CardContent>
    </Card>
  );
}
