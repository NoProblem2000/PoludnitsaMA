import type { PackageCategory, ProfileMetric, Subsystem, ThreadState } from '@/api/types';
import { palette } from '@/theme/palette';
import { formatBytes } from '@/utils/format';

export const METRIC_LABEL: Record<ProfileMetric, string> = {
  CPU: 'CPU Time',
  WALL: 'Wall-Clock',
  ALLOC: 'Allocations',
};

export const METRIC_HINT: Record<ProfileMetric, string> = {
  CPU: 'Только время на CPU: jdk.ExecutionSample (+ NativeMethodSample вне ожиданий)',
  WALL: 'Реальное время: CPU + ThreadPark, JavaMonitorEnter/Wait, Sleep, SocketRead/Write, FileRead/Write',
  ALLOC: 'Выделенные байты: jdk.ObjectAllocationSample',
};

export const SUBSYSTEM_COLOR: Record<Subsystem, string> = {
  BUSINESS: palette.emerald,
  JDBC: '#4f8cff',
  HIBERNATE: palette.violet,
  HTTP: palette.cyan,
  IO: '#26c6da',
  LOCKS: palette.coral,
  GC: palette.amber,
  FRAMEWORK: '#8d6e63',
  JDK: palette.muted,
};

export const SUBSYSTEM_LABEL: Record<Subsystem, string> = {
  BUSINESS: 'Бизнес-логика',
  JDBC: 'JDBC / БД',
  HIBERNATE: 'Hibernate',
  HTTP: 'HTTP / Undertow',
  IO: 'Socket / File I/O',
  LOCKS: 'Блокировки',
  GC: 'GC паузы',
  FRAMEWORK: 'Фреймворк / сервер',
  JDK: 'JDK',
};

export const STATE_COLOR: Record<ThreadState, string> = {
  RUNNABLE: palette.emerald,
  BLOCKED: palette.coral,
  WAITING: palette.amber,
  IO: palette.cyan,
};

export const STATE_LABEL: Record<ThreadState, string> = {
  RUNNABLE: 'RUNNABLE',
  BLOCKED: 'BLOCKED',
  WAITING: 'WAITING / TIMED_WAITING',
  IO: 'I/O',
};

/** Flame frame colours by package category: application code warm, platform cool. */
export const FLAME_COLOR: Record<PackageCategory, string> = {
  APPLICATION: '#e8743b',
  JAKARTA_EE: '#9575cd',
  WILDFLY: '#7e57c2',
  HAZELCAST: '#d4a017',
  THIRD_PARTY: '#5c8fbf',
  JDK: '#4a6378',
  PRIMITIVE_OR_ARRAY: '#4a6378',
};

export const REGRESSION = '#ff3d71';
export const IMPROVEMENT = '#00c853';

/** A value of a call tree in its unit: microseconds for time, bytes for allocations. */
export function formatValue(value: number, unit: string): string {
  if (unit === 'bytes') {
    return formatBytes(value);
  }
  const ms = value / 1000;
  if (ms >= 60_000) {
    return `${(ms / 60_000).toFixed(1)} мин`;
  }
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(2)} с`;
  }
  return `${ms >= 10 ? ms.toFixed(0) : ms.toFixed(1)} мс`;
}

export function formatMs(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(ms >= 10_000 ? 1 : 2)} с` : `${ms.toFixed(0)} мс`;
}

export function shortClass(className: string): string {
  const dot = className.lastIndexOf('.');
  return dot < 0 ? className : className.slice(dot + 1);
}

export function frameLabel(className: string, methodName: string): string {
  return className ? `${shortClass(className)}.${methodName}` : methodName;
}

/** Diverging colour of a share change: grey around 0, red for more time (regression), green for less. */
export function deltaColor(deltaPercent: number): string {
  const intensity = Math.min(1, Math.abs(deltaPercent) / 10);
  if (intensity < 0.02) {
    return '#4a5160';
  }
  const target = deltaPercent > 0 ? REGRESSION : IMPROVEMENT;
  return mix('#4a5160', target, 0.25 + 0.75 * intensity);
}

function mix(from: string, to: string, t: number): string {
  const channel = (hex: string, offset: number) => parseInt(hex.slice(1 + offset, 3 + offset), 16);
  const out = [0, 2, 4].map((o) => Math.round(channel(from, o) + (channel(to, o) - channel(from, o)) * t)
    .toString(16).padStart(2, '0'));
  return `#${out.join('')}`;
}
