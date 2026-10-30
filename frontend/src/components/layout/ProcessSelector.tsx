import clsx from 'clsx';
import { AlertTriangle, ChevronDown, Cpu, Loader2, RefreshCw } from 'lucide-react';
import { useCallback, useRef, useState } from 'react';
import type { ConnectionState, JvmProcessDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { useClickOutside } from '@/hooks/useClickOutside';
import { selectActiveProcess, useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { PROCESS_TYPE_COLOR } from '@/utils/categories';
import { formatBytes } from '@/utils/format';

const STATE_COLOR: Record<ConnectionState, string> = {
  CONNECTED: palette.emerald,
  CONNECTING: palette.amber,
  RECONNECTING: palette.amber,
  DISCONNECTED: palette.subtle,
  FAILED: palette.coral,
  TERMINATED: palette.coral,
};

function TypeBadge({ process }: { process: JvmProcessDto }) {
  return <Badge color={PROCESS_TYPE_COLOR[process.processType]}>{process.processType.replace('_', ' ')}</Badge>;
}

/** Dropdown of local (and registered remote) JVMs with type badges, PID and heap. */
export function ProcessSelector() {
  const processes = useInspectorStore((s) => s.processes);
  const loading = useInspectorStore((s) => s.processesLoading);
  const error = useInspectorStore((s) => s.processesError);
  const activePid = useInspectorStore((s) => s.activePid);
  const active = useInspectorStore(selectActiveProcess);
  const selectProcess = useInspectorStore((s) => s.selectProcess);
  const refreshProcesses = useInspectorStore((s) => s.refreshProcesses);
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(root, open, close);

  return (
    <div ref={root} className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className={clsx(
          'flex h-9 min-w-72 max-w-md items-center gap-2 rounded-md border px-3 text-left transition-colors',
          open ? 'border-accent-cyan/70 bg-kraken-card' : 'border-kraken-border hover:border-accent-cyan/50',
        )}
      >
        <Cpu size={16} className="shrink-0 text-accent-cyan" />
        {active ? (
          <>
            <TypeBadge process={active} />
            <span className="truncate text-sm font-medium">{active.displayName}</span>
            <span className="ml-auto shrink-0 font-mono text-xs text-kraken-muted">PID {active.pid}</span>
          </>
        ) : (
          <span className="text-sm text-kraken-muted">
            {activePid != null ? `PID ${activePid}` : 'Выберите процесс JVM…'}
          </span>
        )}
        <ChevronDown size={14} className="shrink-0 text-kraken-muted" />
      </button>

      {open && (
        <div className="absolute left-0 top-full z-40 mt-1 w-[34rem] overflow-hidden rounded-lg border border-kraken-border bg-kraken-panel shadow-panel">
          <div className="flex items-center justify-between border-b border-kraken-border px-3 py-2">
            <span className="text-2xs font-semibold uppercase tracking-wider text-kraken-muted">
              Java-процессы на этом хосте
            </span>
            <button
              type="button"
              onClick={() => void refreshProcesses()}
              className="rounded p-1 text-kraken-muted hover:bg-kraken-card hover:text-accent-cyan"
              title="Обновить список"
            >
              {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
            </button>
          </div>
          {error && <div className="border-b border-kraken-border px-3 py-2 text-xs text-accent-coral">{error}</div>}
          <ul className="max-h-96 overflow-y-auto py-1">
            {processes.length === 0 && !loading && (
              <li className="px-3 py-6 text-center text-xs text-kraken-muted">
                Процессы не найдены. Инспектор видит только JVM своего пользователя ОС.
              </li>
            )}
            {processes.map((process) => (
              <li key={process.pid}>
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false);
                    void selectProcess(process.pid);
                  }}
                  className={clsx(
                    'flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-kraken-card',
                    process.pid === activePid && 'bg-kraken-card',
                  )}
                >
                  <span
                    className="h-2 w-2 shrink-0 rounded-full"
                    style={{ backgroundColor: STATE_COLOR[process.connectionState] }}
                    title={process.connectionState}
                  />
                  <TypeBadge process={process} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm">{process.displayName}</span>
                    <span className="block truncate font-mono text-2xs text-kraken-subtle">
                      PID {process.pid}
                      {process.jvmVersion ? ` · JVM ${process.jvmVersion}` : ''}
                      {process.heapUsed != null ? ` · heap ${formatBytes(process.heapUsed)}` : ''}
                      {process.heapMax != null ? ` / ${formatBytes(process.heapMax)}` : ''}
                    </span>
                  </span>
                  {!process.attachable && (
                    <span title={process.attachError ?? 'Attach недоступен'}>
                      <AlertTriangle size={14} className="shrink-0 text-accent-amber" />
                    </span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
