import type { ApiError } from '@/lib/apiError';

/**
 * The shared shape every domain store uses for a server-backed resource.
 *
 * Zustand + Axios with no TanStack Query means loading/error/staleness state has to
 * live somewhere explicit. Writing it once here keeps the per-domain stores small
 * and stops each one inventing its own subtly different status enum.
 */

export type LoadStatus = 'idle' | 'loading' | 'success' | 'error';

export interface Resource<T> {
  data: T | null;
  status: LoadStatus;
  error: ApiError | null;
  /** Epoch ms of the last successful load; null until one succeeds. */
  loadedAt: number | null;
}

export function idle<T>(): Resource<T> {
  return { data: null, status: 'idle', error: null, loadedAt: null };
}

/**
 * Mark a resource as loading while KEEPING the previous data, so a refetch does not
 * blank the table the user is reading. `error` is cleared: a stale error next to a
 * live spinner is just confusing.
 */
export function loading<T>(previous: Resource<T>): Resource<T> {
  return { ...previous, status: 'loading', error: null };
}

export function success<T>(data: T, now: number = Date.now()): Resource<T> {
  return { data, status: 'success', error: null, loadedAt: now };
}

/**
 * Record a failure while KEEPING the last good data, so a screen can show what it
 * has plus a "could not refresh" banner rather than collapsing to an error page.
 */
export function failure<T>(previous: Resource<T>, error: ApiError): Resource<T> {
  return { ...previous, status: 'error', error };
}

/** True when there is nothing to show and nothing in flight yet. */
export function isInitialLoad<T>(resource: Resource<T>): boolean {
  return resource.data === null && (resource.status === 'loading' || resource.status === 'idle');
}

/** True when a successful load returned genuinely nothing -- an empty state, not a spinner. */
export function isEmpty<T>(resource: Resource<{ items: T[] } | T[]>): boolean {
  if (resource.status !== 'success' || resource.data === null) return false;
  const data = resource.data;
  return Array.isArray(data) ? data.length === 0 : data.items.length === 0;
}

/**
 * Wrap a fetch so the three transitions are impossible to get out of order.
 * `set` receives the next Resource; the caller owns where it lives in the store.
 */
export async function track<T>(
  current: Resource<T>,
  set: (next: Resource<T>) => void,
  load: () => Promise<T>,
): Promise<void> {
  set(loading(current));
  try {
    set(success(await load()));
  } catch (cause) {
    // The Axios interceptor has already normalized this to an ApiError.
    set(failure(current, cause as ApiError));
  }
}
