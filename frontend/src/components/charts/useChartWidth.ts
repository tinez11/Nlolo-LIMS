import { useEffect, useRef, useState } from 'react';

/**
 * The drawing's own width in CSS pixels (2026-10-23), so a chart is laid out AT the width it is shown rather than drawn
 * at 640 and scaled: scaled, a half-width card's 12px axis labels came out near 8px. Starts at {@code fallback} until
 * measured, and follows the card as the window resizes.
 */
export function useChartWidth<T extends Element>(fallback = 640) {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);
  useEffect(() => {
    const node = ref.current;
    if (!node || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver((entries) => {
      const w = Math.round(entries[0]?.contentRect.width ?? 0);
      if (w > 0) setWidth(w);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}
