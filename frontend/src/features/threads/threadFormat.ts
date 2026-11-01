import type { JvmThreadState, StackFrameDto, StuckKind } from '@/api/types';
import { palette } from '@/theme/palette';

export const STATE_ORDER: JvmThreadState[] = ['RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'NEW', 'TERMINATED'];

export const THREAD_STATE_COLOR: Record<JvmThreadState, string> = {
  RUNNABLE: palette.emerald,
  BLOCKED: palette.coral,
  WAITING: palette.amber,
  TIMED_WAITING: palette.violet,
  NEW: palette.subtle,
  TERMINATED: palette.subtle,
};

/** One letter per state for the dump matrix. */
export const THREAD_STATE_LETTER: Record<JvmThreadState, string> = {
  RUNNABLE: 'R',
  BLOCKED: 'B',
  WAITING: 'W',
  TIMED_WAITING: 'T',
  NEW: 'N',
  TERMINATED: '×',
};

export const STUCK_LABEL: Record<StuckKind, string> = {
  CPU_LOOP: 'Бесконечный цикл',
  DB_QUERY: 'Долгий SQL',
  IO_WAIT: 'Ожидание I/O',
  LOCK_WAIT: 'Ждёт lock',
  RUNNABLE: 'Висит в RUNNABLE',
};

export const STUCK_COLOR: Record<StuckKind, string> = {
  CPU_LOOP: palette.coral,
  DB_QUERY: palette.amber,
  IO_WAIT: palette.violet,
  LOCK_WAIT: palette.coral,
  RUNNABLE: palette.cyan,
};

export function frameText(frame: StackFrameDto): string {
  const line = frame.lineNumber === -2 ? 'Native Method' : frame.lineNumber >= 0
    ? `${frame.fileName ?? '?'}:${frame.lineNumber}` : frame.fileName ?? 'Unknown Source';
  return `${frame.className}.${frame.methodName}(${line})`;
}

/** "com.acme.Cache@0x7f0a" → "Cache@0x7f0a" */
export function shortLock(lock: string | null | undefined): string {
  if (!lock) {
    return '—';
  }
  const at = lock.indexOf('@');
  const type = at > 0 ? lock.slice(0, at) : lock;
  const simple = type.slice(type.lastIndexOf('.') + 1);
  return at > 0 ? simple + lock.slice(at) : simple;
}

/** "com.acme.Cache.get(Cache.java:20)" → "Cache.get:20" */
export function shortFrame(frame: string | null | undefined): string {
  if (!frame) {
    return '—';
  }
  const paren = frame.indexOf('(');
  const qualified = paren > 0 ? frame.slice(0, paren) : frame;
  const parts = qualified.split('.');
  const method = parts.slice(-2).join('.');
  const line = /:(\d+)\)$/.exec(frame);
  return line ? `${method}:${line[1]}` : method;
}
