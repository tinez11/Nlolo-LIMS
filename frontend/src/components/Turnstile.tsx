import { useEffect, useRef } from 'react';

/**
 * Cloudflare Turnstile, the public quote link's "a person, not a script" check (2026-11-02, the user's choice).
 *
 * Invisible unless Cloudflare needs the visitor to do something. Each token it hands over is single-use and short-lived,
 * and means nothing until the server checks it with Cloudflare -- which is where the decision is made; this only
 * collects it. The widget refreshes an expiring token by itself and hands over the new one.
 *
 * The site key is public by design. Development uses Cloudflare's always-pass test key.
 */
const SITE_KEY: string = import.meta.env.VITE_TURNSTILE_SITE_KEY ?? '1x00000000000000000000AA';
const SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

interface TurnstileApi {
  render: (el: HTMLElement, options: Record<string, unknown>) => string;
  remove: (widgetId: string) => void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

let loading: Promise<TurnstileApi> | null = null;

function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = SCRIPT;
      script.async = true;
      script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('Turnstile did not load')));
      script.onerror = () => {
        loading = null;
        reject(new Error('Turnstile could not be reached'));
      };
      document.head.appendChild(script);
    });
  }
  return loading;
}

export function Turnstile({ onToken, onError, language }: {
  onToken: (token: string) => void;
  onError: () => void;
  language?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  // The latest callbacks, without re-rendering the widget when a parent re-renders.
  const handlers = useRef({ onToken, onError });
  useEffect(() => {
    handlers.current = { onToken, onError };
  });

  useEffect(() => {
    let widgetId: string | null = null;
    let cancelled = false;
    loadTurnstile().then(
      (api) => {
        if (cancelled || !box.current) return;
        widgetId = api.render(box.current, {
          sitekey: SITE_KEY,
          appearance: 'interaction-only',
          language: language ?? 'auto',
          'refresh-expired': 'auto',
          callback: (token: string) => handlers.current.onToken(token),
          'error-callback': () => handlers.current.onError(),
        });
      },
      () => { if (!cancelled) handlers.current.onError(); },
    );
    return () => {
      cancelled = true;
      if (widgetId && window.turnstile) window.turnstile.remove(widgetId);
    };
  }, [language]);

  return <div ref={box} className="flex justify-center" />;
}
