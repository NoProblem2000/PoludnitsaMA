import { Route, X } from 'lucide-react';
import { useState } from 'react';
import type { ClassDiffDto, RetentionPathDto } from '@/api/types';
import { Modal } from '@/components/common/Modal';
import { GcRootPathGraph, REFERENCE_KIND_COLOR, weakestKind } from '@/components/heap/GcRootPathGraph';
import { palette } from '@/theme/palette';
import { formatNumber, formatPercent, formatSignedBytes, splitClassName } from '@/utils/format';

/**
 * Through which roots a grown class is held in the target snapshot: the shapes of sampled GC root paths, most common
 * first. Answers "where does the delta hang", not only "what grew".
 */
export function RetentionPanel({ row, onClose }: { row: ClassDiffDto; onClose(): void }) {
  const [shown, setShown] = useState<RetentionPathDto | null>(null);
  const [, simple] = splitClassName(row.className);
  const delta = row.deltaRetainedBytes ?? row.deltaShallowBytes;
  return (
    <div className="max-h-64 overflow-y-auto border-t border-kraken-border bg-kraken-panel px-4 py-3">
      <div className="flex items-center gap-2">
        <span className="font-mono text-xs font-semibold" title={row.className}>{simple}</span>
        <span className="font-mono text-2xs" style={{ color: delta > 0 ? palette.amber : palette.emerald }}>
          {formatSignedBytes(delta)}, {row.deltaInstances > 0 ? '+' : ''}{formatNumber(row.deltaInstances)} объектов
        </span>
        <span className="text-2xs text-kraken-subtle">— как держится в целевом снапшоте</span>
        <button type="button" onClick={onClose} className="ml-auto text-kraken-subtle hover:text-kraken-text" aria-label="Закрыть">
          <X size={14} />
        </button>
      </div>
      {row.retentionPaths.length === 0 ? (
        <p className="mt-2 text-xs text-kraken-subtle">
          {delta <= 0
            ? 'Класс не вырос: пути удержания показываются для роста.'
            : 'Пути удержания считаются для крупнейших классов deep-снапшота (по retained size). Для гистограмм и классов вне топа их нет.'}
        </p>
      ) : (
        <ul className="mt-2 space-y-1.5">
          {row.retentionPaths.map((path) => {
            const weak = weakestKind(path.steps);
            return (
              <li key={path.signature} className="flex items-center gap-3 rounded-md border border-kraken-border bg-kraken-card px-3 py-2">
                <span className="w-14 shrink-0 text-right font-mono text-xs text-accent-cyan">{formatPercent(path.share * 100, 0)}</span>
                <span className="min-w-0 flex-1">
                  <span className="block break-words font-mono text-2xs text-kraken-text">{path.signature}</span>
                  <span className="block text-2xs text-kraken-subtle">
                    {formatNumber(path.sampledInstances)} из выборки
                    {weak && <span style={{ color: REFERENCE_KIND_COLOR[weak] }}> · через {weak.toLowerCase()}-ссылку</span>}
                  </span>
                </span>
                <button
                  type="button"
                  onClick={() => setShown(path)}
                  className="inline-flex h-6 shrink-0 items-center gap-1 rounded border border-kraken-border px-2 text-2xs text-kraken-muted hover:border-accent-cyan/60 hover:text-accent-cyan"
                >
                  <Route size={11} />
                  Путь
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <Modal
        open={shown != null}
        onClose={() => setShown(null)}
        width="max-w-3xl"
        icon={<Route size={16} className="text-accent-cyan" />}
        title={`Путь удержания ${simple}`}
      >
        {shown && <GcRootPathGraph path={shown.steps} fallbackText={shown.signature} targetLabel="Пример объекта" />}
      </Modal>
    </div>
  );
}
