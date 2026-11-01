import { type MouseEvent as ReactMouseEvent, useEffect, useMemo, useRef, useState } from 'react';
import type { ThreadState, TimelineDto } from '@/api/types';
import { palette, withAlpha } from '@/theme/palette';
import { formatMs, STATE_COLOR, STATE_LABEL } from './profileFormat';

export interface TimeWindow {
  from: number;
  to: number;
}

interface ProfileTimelineProps {
  timeline: TimelineDto;
  selection: TimeWindow | null;
  onSelect(selection: TimeWindow | null): void;
  thread: number | null;
  onThread(thread: number | null): void;
}

const LABEL_WIDTH = 168;
const AXIS_HEIGHT = 20;
const METRIC_LANE = 34;
const THREAD_LANE = 15;
const GAP = 4;
/** a drag shorter than this is a click */
const MIN_DRAG_PX = 4;

interface MetricLane {
  key: string;
  label: string;
  color: string;
  values: (number | null)[];
  /** fixed scale (CPU %) or the lane maximum */
  max: number;
  format(value: number): string;
}

function maxOf(values: (number | null)[]): number {
  return values.reduce<number>((m, v) => (v != null && v > m ? v : m), 0);
}

function useWidth<T extends HTMLElement>(): [React.RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(900);
  useEffect(() => {
    const element = ref.current;
    if (!element) {
      return undefined;
    }
    const observer = new ResizeObserver(([entry]) => entry && setWidth(Math.max(320, Math.floor(entry.contentRect.width))));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

/**
 * dotTrace-style timeline: CPU load, GC pauses, lock contention and I/O lanes over thread state lanes, all on one
 * axis. Dragging selects an interval that filters every panel below; a click on a thread lane filters by thread;
 * a double click clears the interval.
 */
export function ProfileTimeline({ timeline, selection, onSelect, thread, onThread }: ProfileTimelineProps) {
  const [container, width] = useWidth<HTMLDivElement>();
  const [drag, setDrag] = useState<{ start: number; end: number } | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const svg = useRef<SVGSVGElement>(null);

  const plotWidth = width - LABEL_WIDTH - 8;
  const duration = Math.max(1, timeline.durationMs);
  const columnWidth = plotWidth / Math.max(1, timeline.buckets);
  const xOf = (ms: number) => LABEL_WIDTH + (Math.min(Math.max(ms, 0), duration) / duration) * plotWidth;
  const msOf = (x: number) => Math.min(duration, Math.max(0, ((x - LABEL_WIDTH) / plotWidth) * duration));

  const lanes = useMemo<MetricLane[]>(() => {
    const hasCpuLoad = timeline.jvmCpuPercent.some((v) => v != null);
    const cpu: MetricLane = hasCpuLoad
      ? {
        // scaled to the load seen (in steps of 10 %), so a few percent of a many-core host stays visible
        key: 'cpu', label: 'CPU Load %', color: palette.emerald, values: timeline.jvmCpuPercent,
        max: Math.min(100, Math.max(10, Math.ceil((maxOf(timeline.jvmCpuPercent) * 1.25) / 10) * 10)),
        format: (v) => `${v.toFixed(0)} %`,
      }
      : {
        key: 'cpu', label: 'CPU (сэмплы)', color: palette.emerald, values: timeline.samples,
        max: Math.max(1, maxOf(timeline.samples)), format: (v) => `${v.toFixed(0)} сэмпл.`,
      };
    const msLane = (key: string, label: string, color: string, values: number[]): MetricLane => ({
      key, label, color, values, max: Math.max(1, maxOf(values)), format: (v) => formatMs(v),
    });
    return [
      cpu,
      msLane('gc', 'GC Pauses', palette.amber, timeline.gcPauseMs),
      msLane('lock', 'Lock Contention', palette.coral, timeline.lockMs),
      msLane('wait', 'Park / Wait / Sleep', '#c5a3ff', timeline.waitMs),
      msLane('io', 'Socket / File I/O', palette.cyan, timeline.ioMs),
    ];
  }, [timeline]);

  const threadTop = AXIS_HEIGHT + lanes.length * (METRIC_LANE + GAP) + 8;
  const height = threadTop + timeline.threads.length * THREAD_LANE + 6;

  const ticks = useMemo(() => {
    const target = Math.max(2, Math.floor(plotWidth / 90));
    const raw = duration / target;
    const step = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000,
      300_000].find((s) => s >= raw) ?? 600_000;
    const result: number[] = [];
    for (let t = 0; t <= duration; t += step) {
      result.push(t);
    }
    return result;
  }, [duration, plotWidth]);

  function localX(event: ReactMouseEvent | MouseEvent): number {
    const rect = svg.current?.getBoundingClientRect();
    return rect ? event.clientX - rect.left : 0;
  }

  function onMouseDown(event: ReactMouseEvent<SVGSVGElement>) {
    if (event.button !== 0) {
      return;
    }
    const x = localX(event);
    if (x < LABEL_WIDTH) {
      return;
    }
    event.preventDefault();
    setDrag({ start: x, end: x });
    const move = (e: MouseEvent) => setDrag((d) => (d ? { ...d, end: localX(e) } : d));
    const up = (e: MouseEvent) => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      const end = localX(e);
      setDrag(null);
      if (Math.abs(end - x) >= MIN_DRAG_PX) {
        const a = msOf(Math.min(x, end));
        const b = msOf(Math.max(x, end));
        onSelect(b - a >= 1 ? { from: a, to: b } : null);
        return;
      }
      // a click: on a thread lane it filters by that thread
      const rect = svg.current?.getBoundingClientRect();
      const y = rect ? e.clientY - rect.top : 0;
      const lane = Math.floor((y - threadTop) / THREAD_LANE);
      const hit = y >= threadTop ? timeline.threads[lane] : undefined;
      if (hit) {
        onThread(thread === hit.threadIndex ? null : hit.threadIndex);
      }
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  }

  function onMove(event: ReactMouseEvent<SVGSVGElement>) {
    const x = localX(event);
    setHover(x < LABEL_WIDTH ? null : Math.min(timeline.buckets - 1, Math.floor((x - LABEL_WIDTH) / columnWidth)));
  }

  const shaded = drag
    ? { a: Math.max(LABEL_WIDTH, Math.min(drag.start, drag.end)), b: Math.min(LABEL_WIDTH + plotWidth, Math.max(drag.start, drag.end)) }
    : selection ? { a: xOf(selection.from), b: xOf(selection.to) } : null;

  return (
    <div ref={container} className="select-none">
      <div className="mb-1 flex h-5 items-center gap-4 overflow-hidden whitespace-nowrap font-mono text-2xs text-kraken-muted">
        {hover != null ? (
          <>
            <span className="text-kraken-text">{formatMs(hover * timeline.bucketMs)}</span>
            {lanes.map((lane) => {
              const value = lane.values[hover];
              return (
                <span key={lane.key} style={{ color: lane.color }}>
                  {lane.label}: {value == null ? '—' : lane.format(value)}
                </span>
              );
            })}
          </>
        ) : (
          <span className="text-kraken-subtle">
            Протяните мышью по таймлайну, чтобы выбрать интервал · двойной клик — сбросить · клик по потоку — фильтр
          </span>
        )}
      </div>
      <svg
        ref={svg}
        width={width}
        height={height}
        className="block cursor-crosshair"
        onMouseDown={onMouseDown}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        onDoubleClick={() => onSelect(null)}
      >
        {/* time axis */}
        {ticks.map((t) => (
          <g key={t}>
            <line x1={xOf(t)} x2={xOf(t)} y1={AXIS_HEIGHT - 4} y2={height} stroke={withAlpha(palette.border, 0.6)} />
            <text x={xOf(t) > LABEL_WIDTH + plotWidth - 40 ? xOf(t) - 3 : xOf(t) + 3} y={12}
              textAnchor={xOf(t) > LABEL_WIDTH + plotWidth - 40 ? 'end' : 'start'} fill={palette.subtle} fontSize={10}
              fontFamily="JetBrains Mono, monospace">
              {formatMs(t)}
            </text>
          </g>
        ))}

        {/* metric lanes */}
        {lanes.map((lane, i) => {
          const top = AXIS_HEIGHT + i * (METRIC_LANE + GAP);
          const bottom = top + METRIC_LANE;
          const cpuArea = lane.key === 'cpu';
          let path = '';
          if (cpuArea) {
            lane.values.forEach((v, b) => {
              const x = LABEL_WIDTH + b * columnWidth;
              const y = bottom - ((v ?? 0) / lane.max) * (METRIC_LANE - 2);
              path += `${b === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)} L${(x + columnWidth).toFixed(1)},${y.toFixed(1)} `;
            });
            path += `L${(LABEL_WIDTH + plotWidth).toFixed(1)},${bottom} L${LABEL_WIDTH},${bottom} Z`;
          }
          return (
            <g key={lane.key}>
              <rect x={LABEL_WIDTH} y={top} width={plotWidth} height={METRIC_LANE} fill={withAlpha(palette.card, 0.55)} />
              <text x={8} y={top + 14} fill={palette.text} fontSize={11}>{lane.label}</text>
              <text x={8} y={top + 27} fill={palette.subtle} fontSize={10} fontFamily="JetBrains Mono, monospace">
                max {lane.format(lane.key === 'cpu' ? maxOf(lane.values) : lane.max)}
              </text>
              {cpuArea ? (
                <>
                  <path d={path} fill={withAlpha(lane.color, 0.35)} stroke={lane.color} strokeWidth={1} />
                  {timeline.machineCpuPercent.some((v) => v != null) && lane.label === 'CPU Load %' && (
                    <polyline
                      fill="none"
                      stroke={withAlpha(palette.text, 0.45)}
                      strokeDasharray="3 2"
                      points={timeline.machineCpuPercent.map((v, b) =>
                        `${(LABEL_WIDTH + (b + 0.5) * columnWidth).toFixed(1)},${(bottom - (Math.min(v ?? 0, lane.max) / lane.max) * (METRIC_LANE - 2)).toFixed(1)}`).join(' ')}
                    />
                  )}
                </>
              ) : (
                lane.values.map((v, b) => {
                  if (!v) {
                    return null;
                  }
                  const h = Math.max(1.5, (v / lane.max) * (METRIC_LANE - 2));
                  return (
                    <rect
                      key={b}
                      x={LABEL_WIDTH + b * columnWidth}
                      y={bottom - h}
                      width={Math.max(1, columnWidth - 0.5)}
                      height={h}
                      fill={lane.color}
                      opacity={0.85}
                    />
                  );
                })
              )}
            </g>
          );
        })}

        {/* GC pause markers on the GC lane */}
        {timeline.gcPauses.map((pause, i) => (
          <line
            key={i}
            x1={xOf(pause.startMs)}
            x2={xOf(pause.startMs)}
            y1={AXIS_HEIGHT + METRIC_LANE + GAP}
            y2={AXIS_HEIGHT + 2 * METRIC_LANE + GAP}
            stroke={palette.amber}
            strokeWidth={1}
            opacity={0.6}
          >
            <title>{`${pause.label}: ${pause.durationMs.toFixed(1)} мс @ ${formatMs(pause.startMs)}`}</title>
          </line>
        ))}

        {/* thread lanes */}
        {timeline.threads.map((lane, i) => {
          const top = threadTop + i * THREAD_LANE;
          const active = thread === lane.threadIndex;
          const dimmed = thread != null && !active;
          return (
            <g key={lane.threadIndex} opacity={dimmed ? 0.35 : 1}>
              {active && <rect x={0} y={top} width={width} height={THREAD_LANE} fill={withAlpha(palette.cyan, 0.08)} />}
              <text x={8} y={top + 11} fill={active ? palette.cyan : palette.muted} fontSize={10}
                fontFamily="JetBrains Mono, monospace">
                {lane.name.length > 24 ? `${lane.name.slice(0, 23)}…` : lane.name}
                <title>{`${lane.name}\nCPU ${formatMs(lane.cpuMs)} · blocked ${formatMs(lane.blockedMs)} · wait ${formatMs(lane.waitingMs)} · I/O ${formatMs(lane.ioMs)}`}</title>
              </text>
              <rect x={LABEL_WIDTH} y={top + 2} width={plotWidth} height={THREAD_LANE - 4} fill={withAlpha(palette.card, 0.4)} />
              {lane.states.map((state, b) => state && (
                <rect
                  key={b}
                  x={LABEL_WIDTH + b * columnWidth}
                  y={top + 2}
                  width={Math.max(1, columnWidth + 0.3)}
                  height={THREAD_LANE - 4}
                  fill={STATE_COLOR[state as ThreadState]}
                  opacity={0.9}
                />
              ))}
            </g>
          );
        })}

        {/* selection */}
        {shaded && (
          <>
            <rect x={LABEL_WIDTH} y={AXIS_HEIGHT - 4} width={Math.max(0, shaded.a - LABEL_WIDTH)} height={height}
              fill={withAlpha(palette.bg, 0.6)} pointerEvents="none" />
            <rect x={shaded.b} y={AXIS_HEIGHT - 4} width={Math.max(0, LABEL_WIDTH + plotWidth - shaded.b)} height={height}
              fill={withAlpha(palette.bg, 0.6)} pointerEvents="none" />
            <rect x={shaded.a} y={AXIS_HEIGHT - 4} width={Math.max(1, shaded.b - shaded.a)} height={height}
              fill="none" stroke={palette.cyan} strokeWidth={1} pointerEvents="none" />
          </>
        )}
        {hover != null && (
          <line x1={LABEL_WIDTH + (hover + 0.5) * columnWidth} x2={LABEL_WIDTH + (hover + 0.5) * columnWidth}
            y1={AXIS_HEIGHT - 4} y2={height} stroke={withAlpha(palette.text, 0.35)} pointerEvents="none" />
        )}
      </svg>
      <div className="mt-1 flex flex-wrap gap-3 pl-1 text-2xs text-kraken-muted">
        {(Object.keys(STATE_COLOR) as ThreadState[]).map((state) => (
          <span key={state} className="flex items-center gap-1">
            <span className="inline-block h-2 w-3 rounded-sm" style={{ backgroundColor: STATE_COLOR[state] }} />
            {STATE_LABEL[state]}
          </span>
        ))}
      </div>
    </div>
  );
}
