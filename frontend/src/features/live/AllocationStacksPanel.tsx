import { ChevronDown, ChevronRight, Flame, Loader2, Play } from 'lucide-react';
import { useEffect, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { AllocationFrameDto, AllocationStacksDto } from '@/api/types';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { formatBytes, formatDuration, formatPercent, splitClassName } from '@/utils/format';

const REFRESH_MS = 10_000;

function frameText(frame: AllocationFrameDto): string {
  const [, simple] = splitClassName(frame.className);
  return `${simple}.${frame.method}()${frame.line >= 0 ? `:${frame.line}` : ''}`;
}

/**
 * Who allocates a class, from the inspector's JFR recording in the target: the first frame of your code
 * ({@code OrderService.process():142}) under JDK internals, and the full stacks.
 */
export function AllocationStacksPanel({ className }: { className: string }) {
  const pid = useInspectorStore((s) => s.activePid);
  const jfrActive = useInspectorStore((s) => s.latest?.jfrAllocation?.active ?? false);
  const pushToast = useInspectorStore((s) => s.pushToast);
  const [data, setData] = useState<AllocationStacksDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [openStack, setOpenStack] = useState<number | null>(null);

  useEffect(() => {
    if (pid == null || !jfrActive) {
      return undefined;
    }
    let current = true;
    const load = () => api.processes.allocationStacks(pid, className).then(
      (value) => current && (setData(value), setError(null)),
      (e: unknown) => current && setError(errorMessage(e)),
    );
    void load();
    const timer = window.setInterval(() => void load(), REFRESH_MS);
    return () => {
      current = false;
      window.clearInterval(timer);
    };
  }, [pid, jfrActive, className]);

  if (pid == null) {
    return null;
  }
  if (!jfrActive) {
    return (
      <div className="mt-3 flex items-center gap-3 rounded-md border border-dashed border-kraken-border px-3 py-2 text-2xs text-kraken-muted">
        <Flame size={13} className="text-accent-amber" />
        Кто создаёт эти объекты — по стектрейсам JFR-событий аллокаций в целевой JVM (throttled, с низким оверхедом).
        <button
          type="button"
          disabled={starting}
          onClick={() => {
            setStarting(true);
            api.processes.startJfr(pid)
              .then(() => pushToast({ tone: 'success', title: 'JFR-профиль аллокаций запущен', message: 'Первые стеки — через 10–20 с' }))
              .catch((e: unknown) => pushToast({ tone: 'error', title: 'JFR не запущен', message: errorMessage(e) }))
              .finally(() => setStarting(false));
          }}
          className="ml-auto inline-flex h-6 shrink-0 items-center gap-1 rounded border border-accent-amber/60 px-2 text-accent-amber hover:bg-accent-amber/10 disabled:opacity-50"
        >
          {starting ? <Loader2 size={11} className="animate-spin" /> : <Play size={11} />}
          Включить JFR
        </button>
      </div>
    );
  }

  return (
    <div className="mt-3 rounded-md border border-kraken-border bg-kraken-card px-3 py-2">
      <div className="flex items-center gap-2 text-2xs font-semibold uppercase tracking-wider text-kraken-muted">
        <Flame size={12} className="text-accent-amber" />
        Кто создаёт объекты (JFR)
        {data && data.weightBytes > 0 && (
          <span className="ml-auto font-normal normal-case text-kraken-subtle">
            {formatBytes(data.weightBytes)} за {formatDuration(data.windowMs)} · {data.eventTypes.join(', ')}
          </span>
        )}
      </div>
      {error ? (
        <p className="mt-1.5 text-2xs text-accent-coral">{error}</p>
      ) : !data ? (
        <p className="mt-1.5 flex items-center gap-1.5 text-2xs text-kraken-muted"><Loader2 size={11} className="animate-spin" /> Загрузка стеков…</p>
      ) : data.hotspots.length === 0 ? (
        <p className="mt-1.5 text-2xs text-kraken-subtle">
          Пока нет сэмплов аллокаций этого класса: JFR сэмплирует с ограничением частоты, редкие аллокации появятся позже.
        </p>
      ) : (
        <div className="mt-1.5 grid gap-3 lg:grid-cols-2">
          <div>
            <div className="mb-1 text-2xs text-kraken-subtle">Hotspots — первая строка вашего кода</div>
            {data.hotspots.slice(0, 6).map((hotspot) => (
              <div key={frameText(hotspot.frame)} className="flex items-center gap-2 py-0.5" title={hotspot.frame.className}>
                <span className="min-w-0 flex-1 truncate font-mono text-xs"
                  style={{ color: hotspot.frame.application ? palette.emerald : palette.muted }}>
                  {frameText(hotspot.frame)}
                </span>
                <span className="h-1.5 w-20 overflow-hidden rounded-full bg-kraken-border">
                  <span className="block h-full rounded-full bg-accent-amber" style={{ width: `${Math.max(2, hotspot.share * 100)}%` }} />
                </span>
                <span className="w-12 text-right font-mono text-2xs tabular-nums">{formatPercent(hotspot.share * 100, 0)}</span>
              </div>
            ))}
          </div>
          <div>
            <div className="mb-1 text-2xs text-kraken-subtle">Стеки аллокаций</div>
            {data.stacks.slice(0, 5).map((stack, index) => (
              <div key={index} className="py-0.5">
                <button
                  type="button"
                  onClick={() => setOpenStack(openStack === index ? null : index)}
                  className="flex w-full items-center gap-1.5 text-left"
                >
                  {openStack === index ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                  <span className="min-w-0 flex-1 truncate font-mono text-2xs text-kraken-text">
                    {stack.frames.map(frameText).slice(0, 3).join(' ← ')}
                  </span>
                  <span className="font-mono text-2xs tabular-nums text-kraken-muted">{formatPercent(stack.share * 100, 0)}</span>
                </button>
                {openStack === index && (
                  <ol className="ml-5 mt-0.5 space-y-px border-l border-kraken-border pl-2">
                    {stack.frames.map((frame, i) => (
                      <li key={i} className="font-mono text-2xs" style={{ color: frame.application ? palette.emerald : palette.subtle }}>
                        {frame.className}.{frame.method}(){frame.line >= 0 ? `:${frame.line}` : ''}
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
