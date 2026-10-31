import clsx from 'clsx';
import { ArrowDown, Anchor, ChevronsDown, Crosshair, Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { GcRootPathDto, GcRootPathStepDto, PathMode, ReferenceKind } from '@/api/types';
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
  /** an object of a dump kept open: offers recomputing the path with or without weak / soft references */
  live?: { snapshotId: string; objectId: number };
}

export const REFERENCE_KIND_COLOR: Record<ReferenceKind, string> = {
  STRONG: palette.cyan,
  SOFT: palette.violet,
  WEAK: palette.amber,
  FINALIZER: palette.coral,
  PHANTOM: palette.coral,
};

export const REFERENCE_KIND_LABEL: Record<ReferenceKind, string> = {
  STRONG: 'strong',
  SOFT: 'soft — очищается при нехватке памяти',
  WEAK: 'weak — очищается ближайшим GC',
  FINALIZER: 'finalizer — ждёт finalize()',
  PHANTOM: 'phantom — объект уже финализирован',
};

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

function Edge({ reference, kind }: { reference: string | null; kind: ReferenceKind | null }) {
  const strong = kind == null || kind === 'STRONG';
  const color = REFERENCE_KIND_COLOR[kind ?? 'STRONG'];
  return (
    <div className="flex items-center gap-2 py-0.5 pl-6" style={{ color }}>
      <ArrowDown size={14} className="shrink-0" strokeDasharray={strong ? undefined : '3 3'} />
      <span className="font-mono text-2xs">{describeReference(reference)}</span>
      {!strong && kind && (
        <Badge color={color}>{kind.toLowerCase()} reference</Badge>
      )}
    </div>
  );
}

/**
 * Shortest path to a GC root as a vertical chain: root on top, each arrow labelled with the field, static field or
 * array slot that holds the next object, the leaking object at the bottom. Click a block for its full name.
 */
export function GcRootPathGraph({ live, ...props }: GcRootPathGraphProps) {
  const [mode, setMode] = useState<PathMode>('STRONG_ONLY');
  const [state, setState] = useState<{ key: string; path: GcRootPathDto | null; error: string | null } | null>(null);
  const key = live ? `${live.snapshotId}:${live.objectId}:${mode}` : '';

  useEffect(() => {
    if (!live) {
      return undefined;
    }
    let current = true;
    api.snapshots.path(live.snapshotId, live.objectId, mode).then(
      (path) => current && setState({ key, path, error: null }),
      (error: unknown) => current && setState({ key, path: null, error: errorMessage(error) }),
    );
    return () => {
      current = false;
    };
  }, [live?.snapshotId, live?.objectId, mode, key]);

  if (!live) {
    return <PathChain {...props} />;
  }
  const loaded = state?.key === key ? state : null;
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-2xs">
        <span className="flex overflow-hidden rounded-md border border-kraken-border">
          {([['STRONG_ONLY', 'Только сильные ссылки'], ['ALL_REFERENCES', 'Включая weak / soft']] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              onClick={() => setMode(value)}
              className={clsx('px-2.5 py-1', mode === value ? 'bg-accent-cyan/15 text-accent-cyan' : 'text-kraken-muted hover:text-kraken-text')}
            >
              {label}
            </button>
          ))}
        </span>
        {loaded?.path && (
          <span className="text-kraken-subtle">
            просмотрено объектов: {loaded.path.visitedObjects}{loaded.path.truncated ? ' (поиск остановлен на лимите)' : ''}
          </span>
        )}
      </div>
      {!loaded ? (
        <div className="flex items-center gap-2 text-2xs text-kraken-muted"><Loader2 size={12} className="animate-spin" /> Ищем путь в открытом дампе…</div>
      ) : loaded.error ? (
        <>
          <p className="text-2xs text-accent-amber">{loaded.error}</p>
          <PathChain {...props} />
        </>
      ) : (
        <PathChain {...props} path={loaded.path?.steps ?? []} fallbackText={loaded.path?.text} strongPathExists={loaded.path?.strongPathExists} />
      )}
    </div>
  );
}

function PathChain({ path, fallbackText, targetLabel = 'Объект утечки', strongPathExists }: Omit<GcRootPathGraphProps, 'live'> & { strongPathExists?: boolean }) {
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
  const weakest = weakestKind(path);
  const fold = !unfolded && path.length > FOLD_ABOVE;
  const indices = fold
    ? [...Array.from({ length: KEEP_HEAD }, (_, i) => i), -1,
      ...Array.from({ length: KEEP_TAIL }, (_, i) => path.length - KEEP_TAIL + i)]
    : path.map((_, i) => i);

  return (
    <div className="space-y-0">
      {/* Stored paths follow strong references and fall back to a weak one only when no strong path exists. */}
      {weakest && (
        <div className="mb-2 rounded border px-2 py-1 text-2xs" style={{ color: REFERENCE_KIND_COLOR[weakest], borderColor: `${REFERENCE_KIND_COLOR[weakest]}66` }}>
          {strongPathExists
            ? `Кратчайший путь идёт через ${weakest.toLowerCase()}-ссылку, но объект держит и сильный путь (режим «Только сильные ссылки»).`
            : `Только через ${weakest.toLowerCase()}-ссылку (${REFERENCE_KIND_LABEL[weakest]}): сильного пути нет, это не утечка — GC освободит объект.`}
        </div>
      )}
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
              <Edge reference={path[KEEP_HEAD - 1]?.referenceName ?? null} kind={path[KEEP_HEAD - 1]?.referenceKind ?? null} />
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
            {index > 0 && (
              <Edge reference={path[index - 1]?.referenceName ?? null} kind={path[index - 1]?.referenceKind ?? null} />
            )}
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

/** Weakest link of a chain: strong > soft > weak > finalizer > phantom; null when all hops are strong. */
export function weakestKind(path: readonly GcRootPathStepDto[]): ReferenceKind | null {
  const order: ReferenceKind[] = ['SOFT', 'WEAK', 'FINALIZER', 'PHANTOM'];
  let weakest: ReferenceKind | null = null;
  for (const step of path) {
    const kind = step.referenceKind;
    if (kind && kind !== 'STRONG' && (weakest == null || order.indexOf(kind) > order.indexOf(weakest))) {
      weakest = kind;
    }
  }
  return weakest;
}
