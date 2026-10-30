import clsx from 'clsx';
import { ArrowDown, Anchor, ChevronsDown, Crosshair } from 'lucide-react';
import { useState } from 'react';
import type { GcRootPathStepDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { palette } from '@/theme/palette';
import {
  anchorOf,
  classifyRoot,
  describeReference,
  formatObjectId,
  ROOT_TYPE_COLOR,
  ROOT_TYPE_LABEL,
} from '@/utils/gcRoots';
import { splitClassName } from '@/utils/format';

interface GcRootPathGraphProps {
  /** GC root first, the inspected object last */
  path: readonly GcRootPathStepDto[];
  /** the backend's one-line rendering, shown when there is no structured path */
  fallbackText?: string | null;
  /** what the last block is: "Объект утечки", "Доминатор" */
  targetLabel?: string;
}

/** Longer chains show their ends and fold the middle. */
const FOLD_ABOVE = 9;
const KEEP_HEAD = 3;
const KEEP_TAIL = 4;

function Block({ step, role, rootLabel, rootColor, selected, onSelect, targetLabel }: {
  step: GcRootPathStepDto;
  role: 'root' | 'hop' | 'target';
  rootLabel: string;
  rootColor: string;
  selected: boolean;
  onSelect(): void;
  targetLabel: string;
}) {
  const isClass = step.className.startsWith('class ');
  const [pkg, simple] = splitClassName(isClass ? step.className.slice('class '.length) : step.className);
  const anchor = anchorOf(step);
  const color = role === 'root' ? rootColor : role === 'target' ? palette.coral : palette.border;
  return (
    <button
      type="button"
      onClick={onSelect}
      className={clsx(
        'w-full rounded-md border bg-kraken-card px-3 py-2 text-left transition-colors hover:bg-kraken-hover',
        selected && 'ring-1 ring-accent-cyan/60',
      )}
      style={{ borderColor: color, boxShadow: role === 'hop' ? undefined : `0 0 10px ${color}33` }}
    >
      <span className="flex items-center gap-2">
        {role === 'root' && (
          <Badge color={rootColor} solid>
            <Anchor size={10} />
            GC Root · {rootLabel}
          </Badge>
        )}
        {role === 'target' && (
          <Badge color={palette.coral} solid>
            <Crosshair size={10} />
            {targetLabel}
          </Badge>
        )}
        {anchor && role !== 'root' && <Badge color={palette.violet}>{anchor.label}</Badge>}
        <span className="ml-auto font-mono text-2xs text-kraken-subtle">{formatObjectId(step.objectId)}</span>
      </span>
      <span className="mt-1 block truncate font-mono text-xs" title={step.className}>
        {isClass && <span className="text-accent-amber">class </span>}
        <span className="text-kraken-subtle">{pkg}</span>
        <span className="font-semibold text-kraken-text">{simple}</span>
      </span>
      {selected && (
        <span className="mt-1.5 block space-y-0.5 border-t border-kraken-border pt-1.5 text-2xs text-kraken-muted">
          <span className="block break-all font-mono">{step.className}</span>
          {step.gcRootKind && <span className="block">Тип корня в HPROF: {step.gcRootKind}</span>}
          {isClass && <span className="block">Объект java.lang.Class: держит static-поля класса, пока жив его ClassLoader.</span>}
        </span>
      )}
    </button>
  );
}

function Edge({ reference }: { reference: string | null }) {
  return (
    <div className="flex items-center gap-2 py-0.5 pl-6">
      <ArrowDown size={14} className="shrink-0 text-accent-cyan" />
      <span className="font-mono text-2xs text-accent-cyan/90">{describeReference(reference)}</span>
    </div>
  );
}

/**
 * Shortest path to a GC root as a vertical chain: root on top, each arrow labelled with the field, static field or
 * array slot that holds the next object, the leaking object at the bottom. Click a block for its full name.
 */
export function GcRootPathGraph({ path, fallbackText, targetLabel = 'Объект утечки' }: GcRootPathGraphProps) {
  const [selected, setSelected] = useState<number | null>(null);
  const [unfolded, setUnfolded] = useState(false);

  if (path.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-kraken-border px-3 py-4 text-xs text-kraken-muted">
        {fallbackText ? <code className="break-all font-mono text-kraken-text">{fallbackText}</code>
          : 'Путь до GC Root не сохранён для этого объекта.'}
      </div>
    );
  }

  const rootType = classifyRoot(path);
  const reachesRoot = path[0]?.gcRoot === true;
  const fold = !unfolded && path.length > FOLD_ABOVE;
  const indices = fold
    ? [...Array.from({ length: KEEP_HEAD }, (_, i) => i), -1,
      ...Array.from({ length: KEEP_TAIL }, (_, i) => path.length - KEEP_TAIL + i)]
    : path.map((_, i) => i);

  return (
    <div className="space-y-0">
      {!reachesRoot && (
        <div className="mb-2 rounded border border-accent-amber/40 bg-accent-amber/10 px-2 py-1 text-2xs text-accent-amber">
          Путь обрезан по глубине или объект недостижим: верхний блок — не GC Root.
        </div>
      )}
      {indices.map((index) => {
        if (index === -1) {
          const hidden = path.length - KEEP_HEAD - KEEP_TAIL;
          return (
            <div key="fold">
              <Edge reference={path[KEEP_HEAD - 1]?.referenceName ?? null} />
              <button
                type="button"
                onClick={() => setUnfolded(true)}
                className="flex w-full items-center justify-center gap-1.5 rounded-md border border-dashed border-kraken-border py-1.5 text-2xs text-kraken-muted hover:border-accent-cyan/60 hover:text-accent-cyan"
              >
                <ChevronsDown size={12} />
                ещё {hidden} промежуточных ссылок
              </button>
            </div>
          );
        }
        const step = path[index];
        if (!step) {
          return null;
        }
        const role = index === 0 && reachesRoot ? 'root' : index === path.length - 1 ? 'target' : 'hop';
        return (
          <div key={`${step.objectId}-${index}`}>
            {/* referenceName is how the previous object holds this one */}
            {index > 0 && <Edge reference={path[index - 1]?.referenceName ?? null} />}
            <Block
              step={step}
              role={role}
              rootLabel={ROOT_TYPE_LABEL[rootType]}
              rootColor={ROOT_TYPE_COLOR[rootType]}
              selected={selected === index}
              onSelect={() => setSelected(selected === index ? null : index)}
              targetLabel={targetLabel}
            />
          </div>
        );
      })}
    </div>
  );
}
