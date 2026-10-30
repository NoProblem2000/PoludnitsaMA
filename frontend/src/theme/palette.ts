// GitKraken palette for code that cannot use Tailwind classes (ECharts, inline styles).
// Must match tailwind.config.js.
export const palette = {
  bg: '#1b1d23',
  panel: '#232730',
  card: '#2c313c',
  border: '#363c4a',
  text: '#e6e8ee',
  muted: '#9aa3b2',
  subtle: '#6b7385',
  cyan: '#00e5ff',
  emerald: '#00c853',
  amber: '#ffab00',
  coral: '#ff3d71',
  violet: '#b388ff',
} as const;

export function withAlpha(hex: string, alpha: number): string {
  const value = hex.replace('#', '');
  const r = parseInt(value.slice(0, 2), 16);
  const g = parseInt(value.slice(2, 4), 16);
  const b = parseInt(value.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/** 0 → emerald (healthy), 50 → amber (suspect), 100 → coral (critical). */
export function scoreColor(score: number): string {
  const s = Math.max(0, Math.min(100, score));
  return s <= 50 ? mix(palette.emerald, palette.amber, s / 50) : mix(palette.amber, palette.coral, (s - 50) / 50);
}

function mix(from: string, to: string, t: number): string {
  const a = from.replace('#', '');
  const b = to.replace('#', '');
  const channel = (offset: number) => {
    const x = parseInt(a.slice(offset, offset + 2), 16);
    const y = parseInt(b.slice(offset, offset + 2), 16);
    return Math.round(x + (y - x) * t)
      .toString(16)
      .padStart(2, '0');
  };
  return `#${channel(0)}${channel(2)}${channel(4)}`;
}
