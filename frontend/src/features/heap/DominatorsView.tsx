import { Network } from 'lucide-react';
import { EmptyState } from '@/components/common/EmptyState';
import { useInspectorStore } from '@/store/useInspectorStore';

/** Placeholder until the dominator tree and sunburst (Step 6). */
export function DominatorsView() {
  const deep = useInspectorStore((s) => s.snapshots.filter((x) => x.kind === 'DEEP' && x.status === 'READY').length);
  return (
    <EmptyState icon={<Network size={22} />} title="Dominator Tree & Sunburst">
      {deep === 0
        ? 'Нужен хотя бы один Deep Heap Dump: retained size и пути до GC Root считаются при его анализе.'
        : `Готовых deep-снапшотов: ${deep}. Дерево доминаторов и sunburst по группам памяти — на Шаге 6.`}
    </EmptyState>
  );
}
