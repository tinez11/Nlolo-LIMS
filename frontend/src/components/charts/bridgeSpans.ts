export interface BridgeBar {
  label: string;
  /** The server's figure, as a string; a step's size is its magnitude. */
  value: string;
  kind: 'START' | 'UP' | 'DOWN' | 'END';
}

/** Where each bar starts and ends: a start or end figure stands on zero; a step stands on the level before it. */
export function bridgeSpans(bars: readonly BridgeBar[]): { from: number; to: number }[] {
  const spans: { from: number; to: number }[] = [];
  let level = 0;
  for (const b of bars) {
    const v = Math.abs(Number(b.value));
    if (b.kind === 'START' || b.kind === 'END') {
      if (b.kind === 'START') level = Number(b.value);
      spans.push({ from: 0, to: Number(b.value) });
    } else {
      const from = level;
      level = b.kind === 'UP' ? level + v : level - v;
      spans.push({ from, to: level });
    }
  }
  return spans;
}

/** A bar's label in at most two lines of about {@code perLine} characters, the second ending in "…" if it must. */
export function labelLines(label: string, perLine: number): string[] {
  const lines: string[] = [];
  let line = '';
  for (const word of label.split(/\s+/)) {
    const next = line ? `${line} ${word}` : word;
    if (next.length <= perLine || !line) {
      line = next;
    } else {
      lines.push(line);
      line = word;
    }
  }
  if (line) lines.push(line);
  if (lines.length <= 2) return lines;
  const second = lines.slice(1).join(' ');
  return [lines[0]!, second.length > perLine ? `${second.slice(0, perLine - 1)}…` : second];
}
