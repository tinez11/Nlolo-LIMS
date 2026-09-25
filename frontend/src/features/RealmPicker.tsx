import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { REALM_CONFIG, REALMS } from '@/auth/realms';

/**
 * Landing screen at `/`.
 *
 * Necessary rather than decorative: `AuthProvider` needs one `authority`, so the
 * realm must be chosen before any authentication can begin. Realm-scoped routes
 * make that choice a URL, and this is where an arrival with no URL makes it.
 *
 * Staff and agents are built so far. The other two realms are shown as
 * unavailable rather than hidden, because a policyholder landing here should learn
 * that this address is not for them yet -- but they are NOT links, because a link to
 * a working login followed by an empty app is worse than an honest label.
 */
export function RealmPicker() {
  return (
    <div className="grid min-h-full place-items-center px-6 py-16">
      <div className="w-full max-w-md">
        <h1 className="text-lg font-semibold tracking-tight">Life Platform</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Choose how you are signing in.
        </p>

        <ul className="mt-6 space-y-2">
          {REALMS.map((realm) => {
            const config = REALM_CONFIG[realm];
            const available = realm === 'staff' || realm === 'agents';

            return (
              <li key={realm}>
                {available ? (
                  <Link
                    to={`/${config.slug}`}
                    className="flex items-center justify-between gap-4 rounded-lg border border-border bg-surface px-4 py-3 transition-colors hover:border-border-strong hover:bg-hover"
                  >
                    <span className="min-w-0">
                      <span className="block text-sm font-medium">{config.label}</span>
                      <span className="block text-xs text-muted-foreground">
                        {config.description}
                      </span>
                    </span>
                    <ArrowRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  </Link>
                ) : (
                  <div
                    className="flex items-center justify-between gap-4 rounded-lg border border-border border-dashed px-4 py-3 opacity-60"
                    aria-disabled="true"
                  >
                    <span className="min-w-0">
                      <span className="block text-sm font-medium">{config.label}</span>
                      <span className="block text-xs text-muted-foreground">
                        {config.description}
                      </span>
                    </span>
                    <span className="shrink-0 text-xs text-subtle-foreground">
                      Not available yet
                    </span>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
