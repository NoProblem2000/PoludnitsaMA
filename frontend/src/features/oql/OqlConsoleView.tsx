import { useVirtualizer } from '@tanstack/react-virtual';
import clsx from 'clsx';
import { History, Loader2, Play, Route, SquareTerminal, Trash2, X } from 'lucide-react';
import { type KeyboardEvent, type ReactNode, useMemo, useRef, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { OqlResultDto, OqlValueDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { CopyButton } from '@/components/common/CodeBlock';
import { EmptyState } from '@/components/common/EmptyState';
import { Modal } from '@/components/common/Modal';
import { SnapshotSelect } from '@/components/common/SnapshotSelect';
import { GcRootPathGraph } from '@/components/heap/GcRootPathGraph';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { formatBytes, formatDuration, formatNumber, formatTime, splitClassName } from '@/utils/format';
import { formatObjectId } from '@/utils/gcRoots';
import { OQL_TEMPLATES } from './oqlTemplates';

const HISTORY_KEY = 'jvm-inspector.oql-history';
const HISTORY_SIZE = 12;
const ROW_HEIGHT = 32;
const DEFAULT_QUERY = "select { text: s.toString().substring(0, 80), length: s.value.length }\n"
  + 'from java.lang.String s\nwhere s.value.length > 1000';

// OQL is "select <JavaScript> from [instanceof] <class> <var> [where <JavaScript>]": highlight both parts lightly.
const TOKEN = new RegExp(
  [
    String.raw`(?<comment>//[^\n]*|/\*[\s\S]*?\*/)`,
    String.raw`(?<string>"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')`,
    String.raw`(?<keyword>\b(?:select|from|where|instanceof)\b)`,
    String.raw`(?<builtin>\b(?:heap|referrers|referees|reachables|rsizeof|sizeof|objectid|classof|root|top|map|filter|sort|count|sum|length|toArray|contains|unique|identical|forEachReferrer|forEachReferee|allocTrace|toHtml|it|index|array|lhs|rhs)\b)`,
    String.raw`(?<js>\b(?:function|return|var|let|const|if|else|null|true|false|undefined|new|typeof)\b)`,
    String.raw`(?<number>\b\d[\d_]*(?:\.\d+)?\b)`,
  ].join('|'),
  'g',
);

const TOKEN_COLOR: Record<string, string> = {
  comment: palette.subtle,
  string: palette.emerald,
  keyword: palette.cyan,
  builtin: palette.violet,
  js: palette.amber,
  number: palette.amber,
};

function highlight(code: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  for (const match of code.matchAll(TOKEN)) {
    const start = match.index ?? 0;
    if (start > last) {
      nodes.push(code.slice(last, start));
    }
    const kind = Object.entries(match.groups ?? {}).find(([, value]) => value !== undefined)?.[0] ?? 'comment';
    nodes.push(
      <span key={start} style={{ color: TOKEN_COLOR[kind], fontWeight: kind === 'keyword' ? 600 : undefined }}>
        {match[0]}
      </span>,
    );
    last = start + match[0].length;
  }
  // a trailing newline needs a character after it, or the overlay is one line shorter than the textarea
  nodes.push(code.slice(last) + '\n');
  return nodes;
}

function loadHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((q): q is string => typeof q === 'string') : [];
  } catch {
    return [];
  }
}

function saveHistory(history: string[]): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
  } catch {
    // storage blocked: history is a convenience
  }
}

/** Code editor look: line numbers and a highlighted layer under a transparent textarea. */
function QueryEditor({ value, onChange, onRun }: { value: string; onChange(value: string): void; onRun(): void }) {
  const layer = useRef<HTMLPreElement>(null);
  const gutter = useRef<HTMLDivElement>(null);
  const lines = value.split('\n').length;
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      onRun();
    } else if (event.key === 'Tab') {
      event.preventDefault();
      const target = event.currentTarget;
      const start = target.selectionStart;
      const next = value.slice(0, start) + '  ' + value.slice(target.selectionEnd);
      onChange(next);
      requestAnimationFrame(() => target.setSelectionRange(start + 2, start + 2));
    }
  };
  return (
    <div className="flex h-44 overflow-hidden rounded-md border border-kraken-border bg-[#14161b] font-mono text-xs leading-5 focus-within:border-accent-cyan/60">
      <div ref={gutter} className="w-10 shrink-0 select-none overflow-hidden border-r border-kraken-border py-2 pr-2 text-right text-kraken-subtle">
        {Array.from({ length: lines }, (_, i) => <div key={i}>{i + 1}</div>)}
      </div>
      <div className="relative min-w-0 flex-1">
        <pre ref={layer} aria-hidden className="pointer-events-none absolute inset-0 m-0 overflow-hidden whitespace-pre-wrap break-words p-2 text-kraken-text">
          {highlight(value)}
        </pre>
        <textarea
          value={value}
          spellCheck={false}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={onKeyDown}
          onScroll={(event) => {
            if (layer.current) {
              layer.current.scrollTop = event.currentTarget.scrollTop;
            }
            if (gutter.current) {
              gutter.current.scrollTop = event.currentTarget.scrollTop;
            }
          }}
          className="absolute inset-0 h-full w-full resize-none whitespace-pre-wrap break-words bg-transparent p-2 text-transparent caret-accent-cyan outline-none"
          aria-label="OQL-запрос"
        />
      </div>
    </div>
  );
}

function Cell({ value, onPath }: { value: OqlValueDto; onPath(objectId: number, label: string): void }) {
  switch (value.type) {
    case 'INSTANCE': {
      const [, simple] = splitClassName(value.className ?? '');
      return (
        <span className="flex min-w-0 items-center gap-1.5" title={`${value.className} ${formatObjectId(value.objectId ?? 0)}`}>
          <span className="truncate font-mono text-xs text-kraken-text">{value.text}</span>
          <span className="shrink-0 font-mono text-2xs text-kraken-subtle">
            {formatBytes(value.shallowSize)}{value.retainedSize != null && ` / ${formatBytes(value.retainedSize)}`}
          </span>
          {value.objectId != null && (
            <button
              type="button"
              onClick={() => onPath(value.objectId as number, simple)}
              title="Путь до GC Root"
              className="shrink-0 rounded p-0.5 text-kraken-subtle hover:text-accent-cyan"
            >
              <Route size={12} />
            </button>
          )}
        </span>
      );
    }
    case 'CLASS':
      return <span className="truncate font-mono text-xs text-accent-violet" title={value.text}>{value.text}</span>;
    case 'NUMBER':
      return <span className="font-mono text-xs tabular-nums text-accent-amber">{value.text}</span>;
    case 'STRING':
      return <span className="truncate font-mono text-xs text-accent-emerald" title={value.text}>{value.text}</span>;
    case 'BOOLEAN':
      return <span className="font-mono text-xs text-accent-cyan">{value.text}</span>;
    case 'NULL':
      return <span className="font-mono text-xs text-kraken-subtle">{value.text || 'null'}</span>;
    default:
      return <span className="truncate font-mono text-xs text-kraken-muted" title={value.text}>{value.text}</span>;
  }
}

function toTsv(result: OqlResultDto): string {
  const lines = [result.columns.join('\t')];
  for (const row of result.rows) {
    lines.push(row.map((cell) => cell.text.replace(/[\t\n]/g, ' ')).join('\t'));
  }
  return lines.join('\n');
}

/**
 * OQL against a heap dump kept open after its deep snapshot: an editor with templates for WildFly, Hibernate,
 * Hazelcast and the JVM, results in a virtualized table, GC root paths of any object in them.
 */
export function OqlConsoleView() {
  const snapshots = useInspectorStore((s) => s.snapshots);
  const mode = useInspectorStore((s) => s.mode);
  const selectedId = useInspectorStore((s) => s.selectedSnapshotId);
  const selectSnapshot = useInspectorStore((s) => s.selectSnapshot);
  const closeHeap = useInspectorStore((s) => s.closeHeap);
  const refreshSnapshots = useInspectorStore((s) => s.refreshSnapshots);
  const open = useMemo(() => snapshots.filter((s) => s.heapOpen && s.status === 'READY'), [snapshots]);
  const chosen = open.find((s) => s.snapshotId === selectedId) ?? open[open.length - 1] ?? null;

  const [query, setQuery] = useState(DEFAULT_QUERY);
  const [limit, setLimit] = useState(200);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<OqlResultDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<string[]>(loadHistory);
  const [showHistory, setShowHistory] = useState(false);
  const [path, setPath] = useState<{ objectId: number; label: string } | null>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const run = async () => {
    if (!chosen || running || query.trim() === '') {
      return;
    }
    setRunning(true);
    setError(null);
    try {
      const response = await api.snapshots.oql(chosen.snapshotId, { query, limit });
      setResult(response);
      const next = [query, ...history.filter((q) => q !== query)].slice(0, HISTORY_SIZE);
      setHistory(next);
      saveHistory(next);
      scroller.current?.scrollTo({ top: 0 });
    } catch (e) {
      setError(errorMessage(e));
      // the dump may have been closed (idle timeout, newer dump)
      void refreshSnapshots();
    } finally {
      setRunning(false);
    }
  };

  const rows = result?.rows ?? [];
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });
  const grid = result ? `48px repeat(${result.columns.length}, minmax(180px, 1fr))` : '48px 1fr';

  if (mode === 'offline') {
    return (
      <EmptyState icon={<SquareTerminal size={22} />} title="OQL Console">
        OQL выполняется по открытому heap dump на сервере инспектора; офлайн-отчёт хранит только результаты анализа.
      </EmptyState>
    );
  }
  if (!chosen) {
    return (
      <EmptyState icon={<SquareTerminal size={22} />} title="OQL Console — нет открытого дампа">
        Снимите Deep Heap Dump с флажком «Оставить дамп открытым для OQL»: после анализа дамп и его индексы останутся на
        диске, и по нему можно выполнять запросы вида <code>select … from … where …</code> и искать пути до GC Root с
        учётом weak / soft-ссылок. Дамп закрывается кнопкой здесь или после 30 минут простоя.
      </EmptyState>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-kraken-border px-4 py-2.5">
        <h1 className="flex items-center gap-2 text-sm font-semibold">
          <SquareTerminal size={16} className="text-accent-cyan" />
          OQL Console
        </h1>
        <SnapshotSelect label="Открытый дамп" value={chosen.snapshotId} options={open} onChange={selectSnapshot} />
        {chosen.heapExpiresAt && (
          <span className="text-2xs text-kraken-subtle">закроется при простое в {formatTime(chosen.heapExpiresAt)}</span>
        )}
        <button
          type="button"
          onClick={() => void closeHeap(chosen.snapshotId)}
          className="ml-auto inline-flex h-7 items-center gap-1.5 rounded-md border border-kraken-border px-2.5 text-xs text-kraken-muted hover:border-accent-coral/60 hover:text-accent-coral"
          title="Закрыть дамп и удалить его файлы с диска"
        >
          <Trash2 size={13} />
          Закрыть дамп
        </button>
      </div>

      <div className="flex min-h-0 flex-1">
        <aside className="w-72 shrink-0 overflow-y-auto border-r border-kraken-border bg-kraken-panel/60 p-3">
          {OQL_TEMPLATES.map((group) => (
            <section key={group.id} className="mb-3">
              <div className="mb-1 flex items-center gap-1.5 text-2xs font-semibold uppercase tracking-wider" style={{ color: palette[group.color] }}>
                <span className="h-1.5 w-1.5 rounded-sm" style={{ backgroundColor: palette[group.color] }} />
                {group.label}
              </div>
              {group.templates.map((template) => (
                <button
                  key={template.title}
                  type="button"
                  onClick={() => setQuery(template.query)}
                  className="block w-full rounded-md px-2 py-1.5 text-left hover:bg-kraken-card"
                  title={template.query}
                >
                  <span className="block text-xs text-kraken-text">{template.title}</span>
                  <span className="block text-2xs leading-snug text-kraken-subtle">{template.hint}</span>
                </button>
              ))}
            </section>
          ))}
          <p className="text-2xs leading-relaxed text-kraken-subtle">
            Синтаксис VisualVM: <code>select &lt;выражение&gt; from [instanceof] &lt;класс&gt; &lt;имя&gt; [where …]</code>.
            Функции: <code>rsizeof</code>, <code>referrers</code>, <code>top</code>, <code>map</code>, <code>filter</code>,
            <code> heap.objects(…)</code>. Доступ к Java-классам из запроса закрыт.
          </p>
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <div className="space-y-2 border-b border-kraken-border p-3">
            <QueryEditor value={query} onChange={setQuery} onRun={() => void run()} />
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => void run()}
                disabled={running || query.trim() === ''}
                className="inline-flex h-8 items-center gap-1.5 rounded-md bg-accent-cyan px-3 text-xs font-semibold text-kraken-bg hover:bg-accent-cyan/90 disabled:opacity-40"
              >
                {running ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
                Выполнить
              </button>
              <span className="text-2xs text-kraken-subtle">Ctrl+Enter</span>
              <label className="ml-3 flex items-center gap-1.5 text-2xs text-kraken-muted">
                строк не больше
                <input
                  type="number"
                  min={1}
                  max={5000}
                  value={limit}
                  onChange={(event) => setLimit(Math.max(1, Math.min(5000, Number(event.target.value) || 1)))}
                  className="h-7 w-20 rounded border border-kraken-border bg-kraken-bg px-1.5 font-mono text-xs text-kraken-text outline-none focus:border-accent-cyan/60"
                />
              </label>
              <div className="relative ml-auto">
                <button
                  type="button"
                  onClick={() => setShowHistory((v) => !v)}
                  disabled={history.length === 0}
                  className="inline-flex h-7 items-center gap-1.5 rounded-md border border-kraken-border px-2 text-2xs text-kraken-muted hover:text-kraken-text disabled:opacity-40"
                >
                  <History size={12} />
                  История
                </button>
                {showHistory && (
                  <div className="absolute right-0 top-full z-30 mt-1 max-h-72 w-[32rem] overflow-y-auto rounded-md border border-kraken-border bg-kraken-panel py-1 shadow-panel">
                    {history.map((item) => (
                      <button
                        key={item}
                        type="button"
                        onClick={() => {
                          setQuery(item);
                          setShowHistory(false);
                        }}
                        className="block w-full truncate px-3 py-1.5 text-left font-mono text-2xs text-kraken-text hover:bg-kraken-card"
                        title={item}
                      >
                        {item.replace(/\s+/g, ' ')}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
            {error && (
              <div className="flex items-start gap-2 rounded border border-accent-coral/50 bg-accent-coral/10 px-3 py-2 font-mono text-2xs text-accent-coral">
                <X size={12} className="mt-0.5 shrink-0" />
                <span className="break-all">{error}</span>
              </div>
            )}
          </div>

          {result && (
            <div className="flex items-center gap-3 border-b border-kraken-border px-4 py-1.5 text-2xs text-kraken-subtle">
              <span>
                {formatNumber(result.rowCount)} строк{result.truncated && ` (обрезано до ${formatNumber(limit)})`} за{' '}
                {formatDuration(result.durationMs)}
              </span>
              {result.truncated && <Badge color={palette.amber}>есть ещё результаты</Badge>}
              <span className="ml-auto"><CopyButton text={toTsv(result)} label="Копировать как TSV" /></span>
            </div>
          )}

          {result && (
            <div className="grid gap-3 border-b border-kraken-border bg-kraken-panel px-4 py-1.5 text-2xs font-semibold uppercase tracking-wider text-kraken-muted" style={{ gridTemplateColumns: grid }}>
              <span>#</span>
              {result.columns.map((column) => <span key={column} className="truncate normal-case">{column}</span>)}
            </div>
          )}
          <div ref={scroller} className="min-h-0 flex-1 overflow-auto">
            {!result ? (
              <div className="flex h-full items-center justify-center px-8 text-center text-xs text-kraken-subtle">
                Выберите шаблон слева или напишите запрос и нажмите Ctrl+Enter.
              </div>
            ) : rows.length === 0 ? (
              <div className="flex h-full items-center justify-center text-xs text-kraken-subtle">Запрос ничего не вернул.</div>
            ) : (
              <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
                {virtualizer.getVirtualItems().map((item) => {
                  const row = rows[item.index];
                  if (!row) {
                    return null;
                  }
                  return (
                    <div
                      key={item.index}
                      role="row"
                      className={clsx('absolute left-0 top-0 grid w-full items-center gap-3 border-b border-kraken-border/50 px-4 hover:bg-kraken-card',
                        item.index % 2 === 1 && 'bg-kraken-panel/40')}
                      style={{ height: ROW_HEIGHT, transform: `translateY(${item.start}px)`, gridTemplateColumns: grid }}
                    >
                      <span className="font-mono text-2xs text-kraken-subtle">{item.index + 1}</span>
                      {row.map((cell, index) => (
                        <span key={index} className="min-w-0">
                          <Cell value={cell} onPath={(objectId, label) => setPath({ objectId, label })} />
                        </span>
                      ))}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>

      <Modal
        open={path != null}
        onClose={() => setPath(null)}
        width="max-w-3xl"
        icon={<Route size={16} className="text-accent-cyan" />}
        title={path ? `Путь до GC Root: ${path.label} ${formatObjectId(path.objectId)}` : ''}
      >
        {path && (
          <GcRootPathGraph
            path={[]}
            targetLabel="Объект"
            live={{ snapshotId: chosen.snapshotId, objectId: path.objectId }}
          />
        )}
      </Modal>
    </div>
  );
}
