import type { ReactNode } from 'react';
import Link from 'next/link';
import { auth, signOut } from '@/auth';
import { Button } from '@/components/ui/button';

/**
 * Shell every customer-facing screen nests under: header with the signed-in user and a sign-out
 * link, plus primary nav. `auth()` (not `getToken`) is correct here — we only need the
 * session-shaped display fields NextAuth's default jwt-strategy merge already puts on
 * `session.user`, never the access token itself (that stays server-only inside `lib/backend.ts`).
 */
export default async function PortalLayout({ children }: { children: ReactNode }) {
  const session = await auth();
  const displayName = session?.user?.name ?? session?.user?.email ?? 'Signed in';

  return (
    <div className="flex min-h-full flex-1 flex-col">
      <header className="flex items-center justify-between border-b border-border px-6 py-3">
        <nav className="flex items-center gap-4 text-sm font-medium">
          <Link href="/">Policies</Link>
          <Link href="/claims">Claims</Link>
        </nav>
        <div className="flex items-center gap-3 text-sm">
          <span className="text-muted-foreground">{displayName}</span>
          <form
            action={async () => {
              'use server';
              await signOut();
            }}
          >
            <Button type="submit" variant="ghost" size="sm">
              Sign out
            </Button>
          </form>
        </div>
      </header>
      <main className="flex-1 px-6 py-6">{children}</main>
    </div>
  );
}
