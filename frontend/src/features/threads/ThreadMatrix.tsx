import clsx from 'clsx';
import { useMemo, useState } from 'react';
import type { ThreadSeriesAnalysisDto, ThreadSeriesRowDto } from '@/api/types';
import { withAlpha } from '@/theme/palette';
import { formatDuration } from '@/utils/format';
import { THREAD_STATE_COLOR, THREAD_STATE_LETTER, frameText } from './threadFormat';

type RowFilter = 'interesting' | 'server' | 'all';

const FILTER_LABEL: Record<RowFilter, string> = {
  interesting: 'Не idle',
  server: 'WildFly workers',
  all: 'Все',
};

/**
 * Thread × Dump matrix: one row per thread, one cell per dump with the state letter, "=" when the stack is the same
 * as in the previous dump. Clicking a cell shows its stack.
 */
export function ThreadMatrix({ analysis }: { analysis: ThreadSeriesAnalysisDto }) {
  const [filter, setFilter] = useState<RowFilter>('interesting');
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<{ row: ThreadSeriesRowDto; dump: number } | null>(null);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return analysis.rows.filter((row) => {
      if (q && !row.name.toLowerCase().includes(q)) {
        return false;
      }
      if (filter === 'server') {
        return row.serverThread;
      }
      return filter === 'all' || !row.idle || row.stuck;
    });
  }, [analysis.rows, filter, query]);

  const pickedCell = picked ? picked.row.cells[picked.dump] : null;
  const pickedStack = pickedCell ? analysis.stacks[pickedCell.stackId] ?? [] : [];

  return (
    <section className="rounded-md border border-kraken-border bg-kraken-panel">
      <header className="flex flex-wrap items-center gap-2 border-b border-kraken-border px-3 py-2 text-xs">
        <span className="font-semibold">Thread × Dump</span>
        <span className="text-kraken-subtle">«=» — тот же стек, что в предыдущем дампе</span>
        <span className="ml-auto flex gap-1">
          {(Object.keys(FILTER_LABEL) as RowFilter[]).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setFilter(f)}
              className={clsx(
                'rounded border px-2 py-0.5 text-2xs',
                f === filter ? 'border-accent-cyan text-accent-cyan' : 'border-kraken-border text-kraken-muted',
              )}
            >
              {FILTER_LABEL[f]}
            </button>
          ))}
        </span>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Фильтр по имени"
          className="h-6 w-40 rounded border border-kraken-border bg-kraken-bg px-2 text-2xs outline-none focus:border-accent-cyan"
        />
      </header>
      <div className="max-h-[28rem] overflow-auto">
        <table className="text-xs">
          <thead className="sticky top-0 bg-kraken-panel text-2xs text-kraken-subtle">
            <tr>
              <th className="sticky left-0 bg-kraken-panel px-3 py-1.5 text-left">Поток ({rows.length})</th>
              {analysis.dumps.map((d) => (
                <th key={d.index} className="w-9 px-1 text-center font-mono" title={d.capturedAt ?? undefined}>
                  #{d.index + 1}
                  {d.offsetMs != null && <div className="font-normal">{formatDuration(d.offsetMs)}</div>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.name}-${row.threadId ?? ''}`} className="border-t border-kraken-border/40">
                <td className="sticky left-0 max-w-72 truncate bg-kraken-panel px-3 py-0.5" title={row.name}>
                  <span className={clsx(row.stuck ? 'font-semibold text-accent-coral' : 'text-kraken-text')}>{row.name}</span>
                  {row.serverThread && <span className="ml-1 text-2xs text-accent-cyan">worker</span>}
                  {row.clusterId != null && <span className="ml-1 text-2xs text-accent-violet">C{row.clusterId}</span>}
                </td>
                {row.cells.map((cell, dump) => {
                  if (!cell?.state) {
                    return <td key={dump} className="text-center text-kraken-subtle">·</td>;
                  }
                  const color = THREAD_STATE_COLOR[cell.state];
                  const active = picked?.row === row && picked.dump === dump;
                  return (
                    <td key={dump} className="p-0.5 text-center">
                      <button
                        type="button"
                        onClick={() => setPicked(active ? null : { row, dump })}
                        title={`${cell.state}${cell.topFrame ? `\n${cell.topFrame}` : ''}${cell.lockName ? `\nlock ${cell.lockName}` : ''}`}
                        className={clsx('h-5 w-8 rounded font-mono text-2xs font-bold', active && 'ring-1 ring-accent-cyan')}
                        style={{ color, backgroundColor: withAlpha(color, cell.sameAsPrevious ? 0.28 : 0.12) }}
                      >
                        {cell.sameAsPrevious ? '=' : THREAD_STATE_LETTER[cell.state]}
                      </button>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {picked && pickedCell && (
        <div className="border-t border-kraken-border px-3 py-2">
          <div className="mb-1 text-xs">
            <span className="font-semibold">{picked.row.name}</span>
            <span className="text-kraken-muted"> · дамп #{picked.dump + 1} · {pickedCell.state}</span>
            {pickedCell.lockName && <span className="text-accent-amber"> · lock {pickedCell.lockName}</span>}
            {pickedCell.cpuDeltaMs != null && <span className="text-kraken-muted"> · CPU +{pickedCell.cpuDeltaMs} ms</span>}
          </div>
          <pre className="max-h-56 overflow-auto font-mono text-2xs leading-relaxed text-kraken-muted">
            {pickedStack.map((f) => `at ${frameText(f)}${f.locks.length ? `  [${f.locks.join(', ')}]` : ''}`).join('\n') || '(пустой стек)'}
          </pre>
        </div>
      )}
    </section>
  );
}
