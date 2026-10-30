import { GitCompare } from 'lucide-react';
import { EmptyState } from '@/components/common/EmptyState';
import { comparePair, useInspectorStore } from '@/store/useInspectorStore';
import { formatTime } from '@/utils/format';

/** Placeholder until the snapshot comparison screen (Step 6). */
export function DiffView() {
  const pair = useInspectorStore(comparePair);
  return (
    <EmptyState icon={<GitCompare size={22} />} title="Snapshot Comparison (Diff)">
      {pair
        ? `Выбрано: ${formatTime(pair[0].createdAt)} → ${formatTime(pair[1].createdAt)}. Таблица дельт с ранжированием по аномальному выживанию — на Шаге 6.`
        : 'Отметьте два снапшота значком сравнения на нижней панели.'}
    </EmptyState>
  );
}
