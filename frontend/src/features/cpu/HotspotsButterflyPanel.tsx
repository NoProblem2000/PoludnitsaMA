import clsx from 'clsx';
import { ArrowDownToLine, ArrowUpFromLine, ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import type { ButterflyDto, FlameNodeDto, HotspotDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { withAlpha } from '@/theme/palette';
import { formatNumber } from '@/utils/format';
import { formatValue, SUBSYSTEM_COLOR, SUBSYSTEM_LABEL } from './profileFormat';

export interface MethodRef {
  className: string;
  methodName: string;
}

function Share({ value, total, color }: { value: number; total: number; color: string }) {
  const percent = total > 0 ? (value / total) * 100 : 0;
  return (
    <div className="relative h-4 w-24 overflow-hidden rounded-sm bg-kraken-bg">
      <div className="absolute inset-y-0 left-0" style={{ width: `${Math.min(100, percent)}%`, backgroundColor: withAlpha(color, 0.55) }} />
      <span className="relative block px-1 text-right font-mono text-2xs leading-4 text-kraken-text">{percent.toFixed(1)} %</span>
    </div>
  );
}

function TreeRow({ node, depth, total, unit, color, expanded, toggle, onOpen, path }: {
  node: FlameNodeDto;
  depth: number;
  total: number;
  unit: string;
  color: string;
  expanded: Set<string>;
  toggle(path: string): void;
  onOpen(method: MethodRef): void;
  path: string;
}) {
  const open = expanded.has(path);
  const leaf = node.children.length === 0;
  return (
    <>
      <div
        className="group flex items-center gap-2 border-b border-kraken-border/40 px-2 py-0.5 hover:bg-kraken-card"
        style={{ paddingLeft: 8 + depth * 14 }}
      >
        <button
          type="button"
          onClick={() => !leaf && toggle(path)}
          className={clsx('shrink-0 text-kraken-subtle', leaf && 'invisible')}
        >
          {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        </button>
        <button
          type="button"
          onDoubleClick={() => onOpen({ className: node.className, methodName: node.methodName })}
          title={`${node.className}.${node.methodName} — двойной клик: сделать центром butterfly`}
          className="min-w-0 flex-1 truncate text-left font-mono text-2xs text-kraken-text"
        >
          <span className="text-kraken-muted">{node.className}.</span>{node.methodName}
        </button>
        <span className="w-20 shrink-0 text-right font-mono text-2xs text-kraken-muted">{formatValue(node.totalValue, unit)}</span>
        <Share value={node.totalValue} total={total} color={color} />
      </div>
      {open && node.children.map((child, i) => (
        <TreeRow key={i} node={child} depth={depth + 1} total={total} unit={unit} color={color} expanded={expanded}
          toggle={toggle} onOpen={onOpen} path={`${path}/${i}`} />
      ))}
    </>
  );
}

/** Opens the heaviest path a few levels deep. */
function initialExpansion(root: FlameNodeDto, levels = 4): Set<string> {
  const result = new Set<string>();
  let node: FlameNodeDto | undefined = root;
  let path = 'r';
  for (let i = 0; i < levels && node; i++) {
    result.add(path);
    path = `${path}/0`;
    node = node.children[0];
  }
  return result;
}

function ButterflyTree({ title, icon, root, unit, total, color, onOpen }: {
  title: string;
  icon: React.ReactNode;
  root: FlameNodeDto;
  unit: string;
  total: number;
  color: string;
  onOpen(method: MethodRef): void;
}) {
  const [expanded, setExpanded] = useState<Set<string>>(() => initialExpansion(root));
  useEffect(() => setExpanded(initialExpansion(root)), [root]);
  const toggle = (path: string) => setExpanded((current) => {
    const next = new Set(current);
    if (next.has(path)) {
      next.delete(path);
    } else {
      next.add(path);
    }
    return next;
  });
  return (
    <div className="flex min-h-0 flex-col rounded-md border border-kraken-border bg-kraken-panel">
      <div className="flex items-center gap-2 border-b border-kraken-border px-3 py-2 text-xs font-semibold text-kraken-text">
        {icon} {title}
        <span className="ml-auto font-normal text-2xs text-kraken-subtle">{root.children.length} веток</span>
      </div>
      <div className="max-h-80 overflow-y-auto">
        {root.children.length === 0 ? (
          <div className="p-3 text-2xs text-kraken-subtle">Нет данных</div>
        ) : (
          root.children.map((child, i) => (
            <TreeRow key={i} node={child} depth={0} total={total} unit={unit} color={color} expanded={expanded}
              toggle={toggle} onOpen={onOpen} path={`r/${i}`} />
          ))
        )}
      </div>
    </div>
  );
}

interface HotspotsButterflyPanelProps {
  hotspots: HotspotDto[] | null;
  unit: string;
  selected: MethodRef | null;
  onSelect(method: MethodRef): void;
  butterfly: ButterflyDto | null;
  butterflyLoading: boolean;
}

/**
 * dotTrace "Hotspots" with a butterfly: the methods with the most own time on top, and for the selected one who
 * calls it (backtraces, left) and what it calls (right).
 */
export function HotspotsButterflyPanel({ hotspots, unit, selected, onSelect, butterfly, butterflyLoading }: HotspotsButterflyPanelProps) {
  const [filter, setFilter] = useState('');
  const rows = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return (hotspots ?? []).filter((h) => !needle || `${h.className}.${h.methodName}`.toLowerCase().includes(needle));
  }, [hotspots, filter]);

  return (
    <div className="space-y-3">
      <div className="rounded-md border border-kraken-border bg-kraken-panel">
        <div className="flex items-center gap-3 border-b border-kraken-border px-3 py-2">
          <span className="text-xs font-semibold text-kraken-text">Hotspots — по собственному (self) времени</span>
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Фильтр…"
            className="ml-auto h-7 w-56 rounded border border-kraken-border bg-kraken-bg px-2 text-xs text-kraken-text outline-none placeholder:text-kraken-subtle"
          />
        </div>
        <div className="max-h-72 overflow-y-auto">
          <table className="w-full text-2xs">
            <thead className="sticky top-0 bg-kraken-panel text-left text-kraken-subtle">
              <tr>
                <th className="px-3 py-1.5 font-medium">Метод</th>
                <th className="px-2 py-1.5 font-medium">Подсистема</th>
                <th className="px-2 py-1.5 text-right font-medium">Self</th>
                <th className="px-2 py-1.5 text-right font-medium">Self %</th>
                <th className="px-2 py-1.5 text-right font-medium">Total</th>
                <th className="px-2 py-1.5 text-right font-medium">Total %</th>
                <th className="px-3 py-1.5 text-right font-medium">Сэмплы</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((h) => {
                const active = selected?.className === h.className && selected.methodName === h.methodName;
                return (
                  <tr
                    key={`${h.className}.${h.methodName}`}
                    onClick={() => onSelect({ className: h.className, methodName: h.methodName })}
                    className={clsx('cursor-pointer border-t border-kraken-border/40',
                      active ? 'bg-accent-cyan/10' : 'hover:bg-kraken-card')}
                  >
                    <td className="max-w-[420px] truncate px-3 py-1 font-mono" title={`${h.className}.${h.methodName}`}>
                      <span className="text-kraken-muted">{h.className}.</span>
                      <span className={active ? 'text-accent-cyan' : 'text-kraken-text'}>{h.methodName}</span>
                    </td>
                    <td className="px-2 py-1">
                      <Badge color={SUBSYSTEM_COLOR[h.subsystem]}>{SUBSYSTEM_LABEL[h.subsystem]}</Badge>
                    </td>
                    <td className="whitespace-nowrap px-2 py-1 text-right font-mono text-kraken-text">{formatValue(h.selfValue, unit)}</td>
                    <td className="px-2 py-1 text-right"><Share value={h.selfPercent} total={100} color={SUBSYSTEM_COLOR[h.subsystem]} /></td>
                    <td className="whitespace-nowrap px-2 py-1 text-right font-mono text-kraken-muted">{formatValue(h.totalValue, unit)}</td>
                    <td className="whitespace-nowrap px-2 py-1 text-right font-mono text-kraken-muted">{h.totalPercent.toFixed(1)} %</td>
                    <td className="px-3 py-1 text-right font-mono text-kraken-subtle">{formatNumber(h.selfSamples)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {hotspots && rows.length === 0 && <div className="p-3 text-2xs text-kraken-subtle">Нет методов в окне</div>}
          {!hotspots && <div className="flex items-center gap-2 p-3 text-2xs text-kraken-muted"><Loader2 size={13} className="animate-spin" /> Загрузка…</div>}
        </div>
      </div>

      {selected && (
        <div className="space-y-2">
          <div className="flex items-center gap-3 font-mono text-xs">
            <span className="text-kraken-muted">Butterfly:</span>
            <span className="truncate text-accent-cyan">{selected.className}.{selected.methodName}</span>
            {butterfly && (
              <span className="text-2xs text-kraken-muted">
                total {formatValue(butterfly.totalValue, unit)} ({butterfly.percent.toFixed(1)} %) · self {formatValue(butterfly.selfValue, unit)}
              </span>
            )}
            {butterflyLoading && <Loader2 size={13} className="animate-spin text-kraken-muted" />}
          </div>
          {butterfly && (
            <div className="grid gap-3 lg:grid-cols-2">
              <ButterflyTree title="Callers (кто вызывает, backtraces)" icon={<ArrowUpFromLine size={14} className="text-accent-violet" />}
                root={butterfly.callers} unit={unit} total={butterfly.totalValue} color="#b388ff" onOpen={onSelect} />
              <ButterflyTree title="Callees (что вызывает)" icon={<ArrowDownToLine size={14} className="text-accent-emerald" />}
                root={butterfly.callees} unit={unit} total={butterfly.totalValue} color="#00c853" onOpen={onSelect} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
