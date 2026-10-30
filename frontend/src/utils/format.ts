const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** 1536 → "1.5 KB"; negative or missing values (the backend's "unknown") → "—". */
export function formatBytes(bytes: number | null | undefined, digits = 1): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) {
    return '—';
  }
  if (bytes < 1024) {
    return `${Math.round(bytes)} B`;
  }
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value >= 100 ? 0 : digits)} ${UNITS[unit]}`;
}

export function formatSignedBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes)) {
    return '—';
  }
  if (bytes === 0) {
    return '±0 B';
  }
  return `${bytes > 0 ? '+' : '−'}${formatBytes(Math.abs(bytes))}`;
}

/** Bytes per minute → "+1.25 MB/min". */
export function formatGrowthRate(bytesPerMin: number | null | undefined): string {
  if (bytesPerMin == null || !Number.isFinite(bytesPerMin)) {
    return '—';
  }
  const mb = bytesPerMin / (1024 * 1024);
  const digits = Math.abs(mb) >= 10 ? 1 : Math.abs(mb) >= 0.1 ? 2 : 3;
  return `${mb > 0 ? '+' : mb < 0 ? '−' : ''}${Math.abs(mb).toFixed(digits)} MB/min`;
}

export function formatRate(bytesPerSec: number | null | undefined): string {
  return bytesPerSec == null ? '—' : `${formatBytes(bytesPerSec)}/s`;
}

const numberFormat = new Intl.NumberFormat('ru-RU');

export function formatNumber(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? '—' : numberFormat.format(Math.round(value));
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  return value == null || !Number.isFinite(value) ? '—' : `${value.toFixed(digits)} %`;
}

export function formatTime(epochMs: number | string | null | undefined): string {
  if (epochMs == null) {
    return '—';
  }
  const date = new Date(epochMs);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleTimeString('ru-RU', { hour12: false });
}

export function formatDateTime(epochMs: number | string | null | undefined): string {
  if (epochMs == null) {
    return '—';
  }
  const date = new Date(epochMs);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ru-RU', { hour12: false });
}

/** 93_784_000 → "1д 02:03:04", 3_723_000 → "01:02:03". */
export function formatUptime(ms: number | null | undefined): string {
  if (ms == null || ms < 0) {
    return '—';
  }
  const total = Math.floor(ms / 1000);
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const clock = [hours, minutes, seconds].map((v) => String(v).padStart(2, '0')).join(':');
  return days > 0 ? `${days}д ${clock}` : clock;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) {
    return '—';
  }
  if (ms < 1000) {
    return `${Math.round(ms)} мс`;
  }
  return ms < 60_000 ? `${(ms / 1000).toFixed(1)} с` : formatUptime(ms);
}

/** "com.acme.orders.OrderCache$Entry" → ["com.acme.orders.", "OrderCache$Entry"]. */
export function splitClassName(className: string): [string, string] {
  const element = className.replace(/(\[\])+$/, '');
  const dot = element.lastIndexOf('.');
  if (dot < 0) {
    return ['', className];
  }
  return [className.slice(0, dot + 1), className.slice(dot + 1)];
}
