import { auth } from '@/auth';
import { NextResponse } from 'next/server';

/**
 * A failed refresh is not an error to display — it means the session is genuinely over (refresh
 * token expired, revoked, or idle past ssoSessionIdleTimeout). Send the user to a real sign-in
 * rather than letting every subsequent backend call 401 with a confusing message.
 */
export default auth((request) => {
  const session = request.auth as ({ error?: string } | null);
  const isSignInRoute = request.nextUrl.pathname.startsWith('/api/auth');

  if (isSignInRoute) {
    return NextResponse.next();
  }
  if (!session || session.error === 'RefreshFailed') {
    const signInUrl = new URL('/api/auth/signin', request.nextUrl.origin);
    return NextResponse.redirect(signInUrl);
  }
  return NextResponse.next();
});

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
