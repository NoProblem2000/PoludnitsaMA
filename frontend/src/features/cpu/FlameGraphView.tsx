import { Search, ZoomOut } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { DiffFlameNodeDto, FlameNodeDto, PackageCategory } from '@/api/types';
import { palette, withAlpha } from '@/theme/palette';
import { deltaColor, FLAME_COLOR, formatValue, frameLabel, IMPROVEMENT, REGRESSION } from './profileFormat';

/** One frame of either a plain or a differential call tree, with a parent link for zooming. */
export interface FlameItem {
  className: string;
  methodName: string;
  category: PackageCategory;
  /** width: total weight (the target's for a diff) */
  value: number;
  self: number;
  percent: number;
  diff: { baselineValue: number; targetValue: number; baselinePercent: number; targetPercent: number; deltaPercent: number } | null;
  children: FlameItem[];
  parent: FlameItem | null;
}

export function fromFlame(node: FlameNodeDto, parent: FlameItem | null = null): FlameItem {
  const item: FlameItem = {
    className: node.className, methodName: node.methodName, category: node.packageCategory, value: node.totalValue,
    self: node.selfValue, percent: node.percent, diff: null, children: [], parent,
  };
  item.children = node.children.map((child) => fromFlame(child, item));
  return item;
}

export function fromDiff(node: DiffFlameNodeDto, parent: FlameItem | null = null): FlameItem {
  const childTarget = node.children.reduce((sum, c) => sum + c.targetValue, 0);
  const item: FlameItem = {
    className: node.className, methodName: node.methodName, category: node.packageCategory, value: node.targetValue,
    self: Math.max(0, node.targetValue - childTarget), percent: node.targetPercent,
    diff: {
      baselineValue: node.baselineValue, targetValue: node.targetValue, baselinePercent: node.baselinePercent,
      targetPercent: node.targetPercent, deltaPercent: node.deltaPercent,
    },
    children: [],
    parent,
  };
  item.children = node.children.filter((c) => c.targetValue > 0).map((child) => fromDiff(child, item));
  return item;
}

interface Rect {
  item: FlameItem;
  x: number;
  y: number;
  w: number;
  ancestor: boolean;
}

const ROW = 18;
const CHAR_PX = 6.6;

function labelOf(item: FlameItem): string {
  return frameLabel(item.className, item.methodName);
}

function matches(item: FlameItem, needle: string): boolean {
  return needle.length > 0 && `${item.className}.${item.methodName}`.toLowerCase().includes(needle);
}

/** Weight of the frames matching the search, nested matches counted once. */
function matchedValue(item: FlameItem, needle: string): number {
  if (matches(item, needle)) {
    return item.value;
  }
  return item.children.reduce((sum, child) => sum + matchedValue(child, needle), 0);
}

interface FlameGraphViewProps {
  root: FlameItem;
  unit: string;
  /** colours by delta instead of package */
  differential?: boolean;
  /** a frame was picked for the butterfly view */
  onPick?(item: FlameItem): void;
}

/**
 * Icicle flame graph (root on top): width is the weight of a frame, a click zooms into it (the ancestors stay as
 * full-width bars to zoom back out), the search highlights frames by class or method name.
 */
export function FlameGraphView({ root, unit, differential = false, onPick }: FlameGraphViewProps) {
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(900);
  const [focus, setFocus] = useState<FlameItem>(root);
  const [hover, setHover] = useState<FlameItem | null>(null);
  const [query, setQuery] = useState('');
  const needle = query.trim().toLowerCase();

  useEffect(() => setFocus(root), [root]);
  useEffect(() => {
    const element = box.current;
    if (!element) {
      return undefined;
    }
    const observer = new ResizeObserver(([entry]) => entry && setWidth(Math.max(320, Math.floor(entry.contentRect.width))));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const rects = useMemo(() => {
    const result: Rect[] = [];
    const chain: FlameItem[] = [];
    for (let p: FlameItem | null = focus.parent; p; p = p.parent) {
      chain.unshift(p);
    }
    chain.forEach((item, depth) => result.push({ item, x: 0, y: depth * ROW, w: width, ancestor: true }));
    const scale = focus.value > 0 ? width / focus.value : 0;
    const layout = (item: FlameItem, x: number, depth: number) => {
      const w = item.value * scale;
      if (w < 0.5) {
        return;
      }
      result.push({ item, x, y: depth * ROW, w, ancestor: false });
      let cx = x;
      for (const child of item.children) {
        layout(child, cx, depth + 1);
        cx += child.value * scale;
      }
    };
    layout(focus, 0, chain.length);
    return result;
  }, [focus, width]);

  const height = rects.reduce((h, r) => Math.max(h, r.y + ROW), ROW) + 2;
  const matched = useMemo(() => (needle ? matchedValue(root, needle) : 0), [root, needle]);

  function colour(item: FlameItem): string {
    if (differential && item.diff) {
      return deltaColor(item.diff.deltaPercent);
    }
    return FLAME_COLOR[item.category] ?? FLAME_COLOR.JDK;
  }

  const shown = hover ?? focus;

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex h-8 w-72 items-center gap-2 rounded-md border border-kraken-border bg-kraken-bg px-2">
          <Search size={13} className="text-kraken-subtle" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Поиск метода или класса…"
            className="min-w-0 flex-1 bg-transparent text-xs text-kraken-text outline-none placeholder:text-kraken-subtle"
          />
        </label>
        {needle && (
          <span className="font-mono text-2xs text-accent-violet">
            совпадения: {formatValue(matched, unit)} ({root.value > 0 ? ((matched / root.value) * 100).toFixed(1) : '0'} %)
          </span>
        )}
        {focus !== root && (
          <button
            type="button"
            onClick={() => setFocus(root)}
            className="inline-flex h-7 items-center gap-1 rounded border border-kraken-border px-2 text-2xs text-kraken-muted hover:text-accent-cyan"
          >
            <ZoomOut size={12} /> Сбросить зум
          </button>
        )}
        {differential && (
          <span className="ml-auto flex items-center gap-3 text-2xs text-kraken-muted">
            <span className="flex items-center gap-1">
              <span className="inline-block h-2 w-3 rounded-sm" style={{ backgroundColor: REGRESSION }} /> регрессия (больше доля)
            </span>
            <span className="flex items-center gap-1">
              <span className="inline-block h-2 w-3 rounded-sm" style={{ backgroundColor: IMPROVEMENT }} /> улучшение
            </span>
          </span>
        )}
      </div>

      <div className="min-h-9 rounded border border-kraken-border bg-kraken-bg px-2 py-1 font-mono text-2xs">
        <div className="truncate text-kraken-text">
          {shown.className ? `${shown.className}.${shown.methodName}` : shown.methodName}
        </div>
        <div className="text-kraken-muted">
          {shown.diff ? (
            <>
              baseline {formatValue(shown.diff.baselineValue, unit)} ({shown.diff.baselinePercent.toFixed(2)} %) → target{' '}
              {formatValue(shown.diff.targetValue, unit)} ({shown.diff.targetPercent.toFixed(2)} %) ·{' '}
              <span style={{ color: shown.diff.deltaPercent > 0 ? REGRESSION : shown.diff.deltaPercent < 0 ? IMPROVEMENT : undefined }}>
                Δ {shown.diff.deltaPercent > 0 ? '+' : ''}{shown.diff.deltaPercent.toFixed(2)} п.п.
              </span>
            </>
          ) : (
            <>
              total {formatValue(shown.value, unit)} ({shown.percent.toFixed(2)} %) · self {formatValue(shown.self, unit)}
            </>
          )}
          {onPick && shown !== root && <span className="text-kraken-subtle"> · ПКМ — открыть в Butterfly</span>}
        </div>
      </div>

      <div ref={box} className="max-h-[560px] overflow-y-auto rounded border border-kraken-border bg-kraken-bg">
        <svg width={width} height={height} className="block">
          {rects.map((r, i) => {
            const label = labelOf(r.item);
            const chars = Math.floor((r.w - 6) / CHAR_PX);
            const hit = matches(r.item, needle);
            const dim = needle.length > 0 && !hit;
            const fill = colour(r.item);
            return (
              <g
                key={i}
                className="cursor-pointer"
                onClick={() => setFocus(r.item === focus && r.item.parent ? r.item.parent : r.item)}
                onContextMenu={(e) => {
                  if (onPick && r.item !== root) {
                    e.preventDefault();
                    onPick(r.item);
                  }
                }}
                onMouseEnter={() => setHover(r.item)}
                onMouseLeave={() => setHover(null)}
              >
                <rect
                  x={r.x + 0.5}
                  y={r.y + 0.5}
                  width={Math.max(0.5, r.w - 1)}
                  height={ROW - 1}
                  rx={2}
                  fill={r.ancestor ? withAlpha(fill, 0.45) : fill}
                  opacity={dim ? 0.3 : 1}
                  stroke={hit ? palette.violet : hover === r.item ? palette.text : 'none'}
                  strokeWidth={hit ? 1.5 : 1}
                />
                {chars >= 3 && (
                  <text x={r.x + 4} y={r.y + 12.5} fontSize={11} fill="#f5f6f8" opacity={dim ? 0.5 : 1} pointerEvents="none"
                    fontFamily="JetBrains Mono, monospace">
                    {label.length > chars ? `${label.slice(0, chars - 1)}…` : label}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}
