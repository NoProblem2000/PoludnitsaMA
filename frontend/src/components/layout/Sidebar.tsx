import clsx from 'clsx';
import { Activity, GitCompare, type LucideIcon, Network, ShieldAlert } from 'lucide-react';
import { useMemo } from 'react';
import {
  countFindings,
  selectActiveProcess,
  useInspectorStore,
  type ViewId,
} from '@/store/useInspectorStore';
import { formatBytes } from '@/utils/format';

interface NavItem {
  id: ViewId;
  label: string;
  hint: string;
  icon: LucideIcon;
}

const ITEMS: NavItem[] = [
  { id: 'live', label: 'Live Memory & Traffic', hint: 'Пулы, GC, Leak Radar', icon: Activity },
  { id: 'heuristics', label: 'Leak Heuristics', hint: 'Почему течёт?', icon: ShieldAlert },
  { id: 'dominators', label: 'Dominator Tree & Sunburst', hint: 'Кто держит память', icon: Network },
  { id: 'diff', label: 'Snapshot Comparison', hint: 'Diff двух снапшотов', icon: GitCompare },
];

function Counter({ value, tone }: { value: number; tone: 'critical' | 'warning' }) {
  if (value === 0) {
    return null;
  }
  return (
    <span
      className={clsx(
        'min-w-5 rounded-full px-1.5 text-center text-2xs font-bold leading-5 text-kraken-bg',
        tone === 'critical' ? 'bg-accent-coral' : 'bg-accent-amber',
      )}
    >
      {value}
    </span>
  );
}

export function Sidebar() {
  const view = useInspectorStore((s) => s.view);
  const setView = useInspectorStore((s) => s.setView);
  const findings = useInspectorStore((s) => s.findings);
  const compareCount = useInspectorStore((s) => s.compareIds.length);
  const latest = useInspectorStore((s) => s.latest);
  const process = useInspectorStore(selectActiveProcess);
  const counts = useMemo(() => countFindings(findings), [findings]);

  return (
    <nav className="flex w-60 shrink-0 flex-col border-r border-kraken-border bg-kraken-panel">
      <ul className="flex-1 space-y-0.5 p-2">
        {ITEMS.map((item) => {
          const Icon = item.icon;
          const active = view === item.id;
          return (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => setView(item.id)}
                className={clsx(
                  'group flex w-full items-center gap-3 rounded-md border-l-2 px-3 py-2 text-left transition-colors',
                  active
                    ? 'border-accent-cyan bg-kraken-card text-accent-cyan'
                    : 'border-transparent text-kraken-muted hover:bg-kraken-card/60 hover:text-kraken-text',
                )}
              >
                <Icon size={17} className="shrink-0" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium">{item.label}</span>
                  <span className="block truncate text-2xs text-kraken-subtle">{item.hint}</span>
                </span>
                {item.id === 'heuristics' && (
                  <span className="flex gap-1">
                    <Counter value={counts.critical} tone="critical" />
                    <Counter value={counts.warning} tone="warning" />
                  </span>
                )}
                {item.id === 'diff' && compareCount > 0 && (
                  <span className="rounded-full border border-accent-violet/60 px-1.5 text-2xs text-accent-violet">
                    {compareCount}/2
                  </span>
                )}
              </button>
            </li>
          );
        })}
      </ul>

      {process && (
        <div className="space-y-1 border-t border-kraken-border p-3 text-2xs text-kraken-muted">
          <div className="truncate text-xs text-kraken-text">{process.displayName}</div>
          <div className="font-mono">PID {process.pid}</div>
          {process.jvmVersion && <div className="truncate font-mono">JVM {process.jvmVersion}</div>}
          {latest && (
            <div className="font-mono">
              heap {formatBytes(latest.heap.used)} / {formatBytes(latest.heap.max > 0 ? latest.heap.max : latest.heap.committed)}
            </div>
          )}
        </div>
      )}
    </nav>
  );
}
