import { ShieldAlert } from 'lucide-react';
import { useMemo } from 'react';
import { EmptyState } from '@/components/common/EmptyState';
import { countFindings, useInspectorStore } from '@/store/useInspectorStore';

/** Placeholder until the findings screen (Step 6); the findings are already loaded into the store. */
export function HeuristicsView() {
  const findings = useInspectorStore((s) => s.findings);
  const counts = useMemo(() => countFindings(findings), [findings]);
  return (
    <EmptyState icon={<ShieldAlert size={22} />} title="Leak Heuristics — «Почему течёт?»">
      {findings.length === 0
        ? 'Диагнозы появятся после Deep Heap Dump: 9 инспекций WildFly / Hazelcast / JVM запускаются во время анализа дампа.'
        : `Найдено ${findings.length} диагнозов: ${counts.critical} критичных, ${counts.warning} предупреждений, ${counts.info} информационных. Карточки с объяснением и кодом исправления — на Шаге 6.`}
    </EmptyState>
  );
}
