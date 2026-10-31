import clsx from 'clsx';
import {
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Info,
  Lightbulb,
  ListChecks,
  Loader2,
  Route,
  ShieldAlert,
  Target,
  Wrench,
} from 'lucide-react';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import type { FindingCategory, InspectionFindingDto, InspectionRuleRunDto, Severity } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { CodeBlock, CopyButton } from '@/components/common/CodeBlock';
import { EmptyState } from '@/components/common/EmptyState';
import { SnapshotSelect } from '@/components/common/SnapshotSelect';
import { GcRootPathGraph } from '@/components/heap/GcRootPathGraph';
import { useChosenDeepSnapshot, useDeepAnalysis } from '@/store/useSnapshotData';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { SEVERITY_COLOR } from '@/utils/categories';
import { classifyRoot, ROOT_TYPE_COLOR, ROOT_TYPE_LABEL } from '@/utils/gcRoots';
import { formatBytes, formatDuration, formatNumber, formatPercent, splitClassName } from '@/utils/format';

const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'WARNING', 'INFO'];

const SEVERITY_LABEL: Record<Severity, string> = {
  CRITICAL: 'Critical',
  WARNING: 'Warning',
  INFO: 'Info',
};

const SEVERITY_ICON: Record<Severity, typeof AlertOctagon> = {
  CRITICAL: AlertOctagon,
  WARNING: AlertTriangle,
  INFO: Info,
};

const CATEGORY_ORDER: FindingCategory[] = ['JAKARTA_WILDFLY', 'HAZELCAST_CLIENT', 'JVM_CORE'];

const CATEGORY_LABEL: Record<FindingCategory, string> = {
  JAKARTA_WILDFLY: 'WildFly / Jakarta EE',
  HAZELCAST_CLIENT: 'Hazelcast Client',
  JVM_CORE: 'JVM Collections',
};

const CATEGORY_COLOR: Record<FindingCategory, string> = {
  JAKARTA_WILDFLY: palette.violet,
  HAZELCAST_CLIENT: palette.amber,
  JVM_CORE: palette.cyan,
};

type CategoryFilter = 'ALL' | FindingCategory;

function byDamage(a: InspectionFindingDto, b: InspectionFindingDto): number {
  return b.wastedMemoryBytes - a.wastedMemoryBytes || a.title.localeCompare(b.title);
}

function FindingItem({ finding, heapBytes, selected, onSelect }: {
  finding: InspectionFindingDto;
  heapBytes: number;
  selected: boolean;
  onSelect(): void;
}) {
  const [, simple] = splitClassName(finding.suspectClassName);
  const share = heapBytes > 0 ? (100 * finding.wastedMemoryBytes) / heapBytes : null;
  const color = SEVERITY_COLOR[finding.severity];
  return (
    <button
      type="button"
      onClick={onSelect}
      className={clsx(
        'flex w-full gap-2.5 border-l-2 px-3 py-2 text-left transition-colors',
        selected ? 'bg-kraken-card' : 'hover:bg-kraken-card/60',
      )}
      style={{ borderLeftColor: selected ? color : 'transparent' }}
    >
      <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: color, boxShadow: `0 0 6px ${color}` }} />
      <span className="min-w-0 flex-1">
        <span className={clsx('block text-xs leading-snug', selected ? 'text-kraken-text' : 'text-kraken-text/90')}>
          {finding.title}
        </span>
        <span className="mt-0.5 flex items-center gap-2 text-2xs text-kraken-subtle">
          <span className="truncate font-mono" title={finding.suspectClassName}>{simple}</span>
          <span className="ml-auto shrink-0 font-mono" style={{ color }}>{formatBytes(finding.wastedMemoryBytes)}</span>
          {share != null && <span className="shrink-0 font-mono">{formatPercent(share)}</span>}
        </span>
      </span>
    </button>
  );
}

function FindingList({ findings, heapBytes, selectedId, onSelect }: {
  findings: InspectionFindingDto[];
  heapBytes: number;
  selectedId: string | null;
  onSelect(id: string): void;
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggle = (key: string) => setCollapsed((current) => {
    const next = new Set(current);
    if (!next.delete(key)) {
      next.add(key);
    }
    return next;
  });

  return (
    <div className="pb-4">
      {SEVERITY_ORDER.map((severity) => {
        const ofSeverity = findings.filter((f) => f.severity === severity);
        if (ofSeverity.length === 0) {
          return null;
        }
        const Icon = SEVERITY_ICON[severity];
        const color = SEVERITY_COLOR[severity];
        const open = !collapsed.has(severity);
        return (
          <section key={severity}>
            <button
              type="button"
              onClick={() => toggle(severity)}
              className="sticky top-0 z-10 flex w-full items-center gap-2 border-b border-kraken-border bg-kraken-panel px-3 py-2 text-left"
              style={{ color }}
            >
              {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
              <Icon size={14} />
              <span className="text-xs font-bold uppercase tracking-wider">{SEVERITY_LABEL[severity]}</span>
              <span className="ml-auto rounded-full px-1.5 text-2xs font-bold text-kraken-bg" style={{ backgroundColor: color }}>
                {ofSeverity.length}
              </span>
            </button>
            {open && CATEGORY_ORDER.map((category) => {
              const items = ofSeverity.filter((f) => f.category === category).sort(byDamage);
              if (items.length === 0) {
                return null;
              }
              return (
                <div key={category}>
                  <div className="flex items-center gap-1.5 px-3 pb-1 pt-2 text-2xs font-semibold uppercase tracking-wider" style={{ color: CATEGORY_COLOR[category] }}>
                    <span className="h-1.5 w-1.5 rounded-sm" style={{ backgroundColor: CATEGORY_COLOR[category] }} />
                    {CATEGORY_LABEL[category]}
                    <span className="text-kraken-subtle">· {items.length}</span>
                  </div>
                  {items.map((finding) => (
                    <FindingItem
                      key={finding.id}
                      finding={finding}
                      heapBytes={heapBytes}
                      selected={finding.id === selectedId}
                      onSelect={() => onSelect(finding.id)}
                    />
                  ))}
                </div>
              );
            })}
          </section>
        );
      })}
    </div>
  );
}

function Section({ icon, title, accent, children, aside }: {
  icon: ReactNode;
  title: string;
  accent: string;
  children: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <section className="rounded-lg border border-kraken-border bg-kraken-panel">
      <header className="flex items-center gap-2 border-b border-kraken-border px-4 py-2.5">
        <span style={{ color: accent }}>{icon}</span>
        <h3 className="text-xs font-semibold uppercase tracking-wider text-kraken-text">{title}</h3>
        {aside && <span className="ml-auto">{aside}</span>}
      </header>
      <div className="p-4">{children}</div>
    </section>
  );
}

function Fact({ label, children, mono = true }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <div className="text-2xs uppercase tracking-wider text-kraken-subtle">{label}</div>
      <div className={clsx('mt-0.5 truncate text-sm text-kraken-text', mono && 'font-mono')}>{children}</div>
    </div>
  );
}

/** Explanations are plain text: blank lines split paragraphs, "•" lines are kept as bullets. */
function Explanation({ text }: { text: string }) {
  const paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  return (
    <div className="space-y-3 text-sm leading-relaxed text-kraken-text/90">
      {paragraphs.map((paragraph, index) => (
        <p key={index} className="whitespace-pre-line">{paragraph}</p>
      ))}
    </div>
  );
}

function CulpritPanel({ finding, heapBytes }: { finding: InspectionFindingDto; heapBytes: number }) {
  const share = heapBytes > 0 ? Math.min(100, (100 * finding.wastedMemoryBytes) / heapBytes) : null;
  const color = SEVERITY_COLOR[finding.severity];
  const [pkg, simple] = splitClassName(finding.suspectClassName);
  return (
    <Section icon={<Target size={15} />} title="Виновник и ущерб" accent={palette.coral}>
      <div className="grid gap-6 2xl:grid-cols-[1fr_auto]">
        <div className="min-w-0 space-y-3">
          <div className="min-w-0">
            <div className="text-2xs uppercase tracking-wider text-kraken-subtle">Класс</div>
            <div className="mt-0.5 break-all font-mono text-sm text-kraken-text">
              <span className="text-kraken-subtle">{pkg}</span>
              <span className="font-semibold">{simple}</span>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Fact label="Поле">{finding.fieldName ?? '—'}</Fact>
            <Fact label="Затронуто объектов">{formatNumber(finding.affectedInstances)}</Fact>
          </div>
        </div>
        <div className="2xl:w-60 2xl:text-right">
          <div className="text-2xs uppercase tracking-wider text-kraken-subtle">Retained Size</div>
          <div className="font-mono text-3xl font-semibold leading-tight" style={{ color }}>
            {formatBytes(finding.wastedMemoryBytes)}
          </div>
          <div className="mt-1 font-mono text-xs text-kraken-muted">
            {share == null ? 'размер кучи неизвестен' : `${formatPercent(share)} всей кучи (${formatBytes(heapBytes)})`}
          </div>
          {share != null && (
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-kraken-border">
              <div className="h-full rounded-full" style={{ width: `${Math.max(1.5, share)}%`, backgroundColor: color }} />
            </div>
          )}
        </div>
      </div>
      {finding.evidence.length > 0 && (
        <div className="mt-4 border-t border-kraken-border pt-3">
          <div className="mb-1.5 text-2xs uppercase tracking-wider text-kraken-subtle">Факты из дампа</div>
          <ul className="space-y-1">
            {finding.evidence.map((fact, index) => (
              <li key={index} className="flex gap-2 text-xs text-kraken-muted">
                <CheckCircle2 size={12} className="mt-0.5 shrink-0 text-accent-cyan" />
                <span className="break-words font-mono">{fact}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Section>
  );
}

function FindingDetail({ finding, heapBytes, openSnapshotId }: {
  finding: InspectionFindingDto;
  heapBytes: number;
  /** set when the dump is open: the path can be searched again, with weak / soft references */
  openSnapshotId: string | null;
}) {
  const target = finding.gcRootPath[finding.gcRootPath.length - 1];
  const rootType = classifyRoot(finding.gcRootPath);
  const SeverityIcon = SEVERITY_ICON[finding.severity];
  return (
    <div className="space-y-4">
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge color={SEVERITY_COLOR[finding.severity]} solid>
            <SeverityIcon size={11} />
            {SEVERITY_LABEL[finding.severity]}
          </Badge>
          <Badge color={CATEGORY_COLOR[finding.category]}>{CATEGORY_LABEL[finding.category]}</Badge>
          <span className="font-mono text-2xs text-kraken-subtle">{finding.ruleId}</span>
        </div>
        <h2 className="mt-2 text-lg font-semibold leading-snug">{finding.title}</h2>
      </div>

      <CulpritPanel finding={finding} heapBytes={heapBytes} />

      <Section
        icon={<Route size={15} />}
        title="Shortest Path to GC Root"
        accent={palette.cyan}
        aside={
          <span className="flex items-center gap-2">
            <Badge color={ROOT_TYPE_COLOR[rootType]}>{ROOT_TYPE_LABEL[rootType]}</Badge>
            {finding.gcRootPathText && <CopyButton text={finding.gcRootPathText} label="Копировать путь" />}
          </span>
        }
      >
        <GcRootPathGraph
          path={finding.gcRootPath}
          fallbackText={finding.gcRootPathText}
          live={openSnapshotId && target ? { snapshotId: openSnapshotId, objectId: target.objectId } : undefined}
        />
      </Section>

      <Section icon={<Lightbulb size={15} />} title="Почему это вызывает утечку в вашей архитектуре" accent={palette.amber}>
        <Explanation text={finding.whyItHappens} />
      </Section>

      <Section icon={<Wrench size={15} />} title="Как исправить в коде" accent={palette.emerald}>
        <CodeBlock code={finding.howToFixCodeSnippet} />
      </Section>
    </div>
  );
}

function RulesSummary({ runs }: { runs: InspectionRuleRunDto[] }) {
  if (runs.length === 0) {
    return null;
  }
  const failed = runs.filter((run) => run.error);
  const total = runs.reduce((sum, run) => sum + run.durationMs, 0);
  return (
    <div className="flex items-center gap-2 border-t border-kraken-border px-3 py-2 text-2xs text-kraken-subtle" title={runs.map((run) => `${run.title}: ${run.findings} (${formatDuration(run.durationMs)})${run.error ? ` — ${run.error}` : ''}`).join('\n')}>
      <ListChecks size={12} />
      {runs.length} инспекций за {formatDuration(total)}
      {failed.length > 0 && <span className="text-accent-coral">· {failed.length} с ошибкой</span>}
    </div>
  );
}

/**
 * "Why is it leaking?": findings of a deep snapshot grouped by severity and subsystem on the left, the selected
 * diagnosis on the right with its culprit, path to the GC root, explanation and fix.
 */
export function HeuristicsAdvisorView() {
  const { choices, chosen } = useChosenDeepSnapshot();
  const selectSnapshot = useInspectorStore((s) => s.selectSnapshot);
  const { data, loading, error } = useDeepAnalysis(chosen);
  const [category, setCategory] = useState<CategoryFilter>('ALL');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const findings = useMemo(() => data?.findings ?? [], [data]);
  const visible = useMemo(
    () => (category === 'ALL' ? findings : findings.filter((f) => f.category === category)),
    [findings, category],
  );
  const heapBytes = chosen?.totalHeapBytes ?? 0;

  // Keep a valid selection: the most damaging critical finding first.
  useEffect(() => {
    if (visible.some((f) => f.id === selectedId)) {
      return;
    }
    const first = SEVERITY_ORDER.flatMap((severity) => visible.filter((f) => f.severity === severity).sort(byDamage))[0];
    setSelectedId(first?.id ?? null);
  }, [visible, selectedId]);

  if (choices.length === 0 || !chosen) {
    return (
      <EmptyState icon={<ShieldAlert size={22} />} title="Leak Heuristics Advisor — «Почему течёт?»">
        Диагнозы появятся после Deep Heap Dump: 11 инспекций WildFly / Jakarta EE, Hazelcast Client и JVM
        запускаются во время анализа дампа.
      </EmptyState>
    );
  }

  const selected = visible.find((f) => f.id === selectedId) ?? null;
  const categoryCount = (value: CategoryFilter) =>
    value === 'ALL' ? findings.length : findings.filter((f) => f.category === value).length;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-kraken-border px-4 py-2.5">
        <h1 className="flex items-center gap-2 text-sm font-semibold">
          <ShieldAlert size={16} className="text-accent-coral" />
          Leak Heuristics Advisor
        </h1>
        <SnapshotSelect label="Снапшот" value={chosen.snapshotId} options={choices} onChange={selectSnapshot} />
        <div className="ml-auto flex flex-wrap gap-1.5">
          {(['ALL', ...CATEGORY_ORDER] as CategoryFilter[]).map((value) => (
            <button
              key={value}
              type="button"
              onClick={() => setCategory(value)}
              className={clsx(
                'h-7 rounded-full border px-3 text-xs transition-colors',
                category === value
                  ? 'border-accent-cyan bg-accent-cyan/15 text-accent-cyan'
                  : 'border-kraken-border text-kraken-muted hover:border-accent-cyan/50 hover:text-kraken-text',
              )}
            >
              {value === 'ALL' ? 'Все' : CATEGORY_LABEL[value]}
              <span className="ml-1.5 font-mono text-2xs opacity-70">{categoryCount(value)}</span>
            </button>
          ))}
        </div>
      </div>

      {loading ? (
        <div className="flex flex-1 items-center justify-center gap-2 text-xs text-kraken-muted">
          <Loader2 size={14} className="animate-spin" /> Загружаем диагнозы…
        </div>
      ) : error ? (
        <EmptyState icon={<AlertTriangle size={22} />} title="Не удалось загрузить диагнозы">{error}</EmptyState>
      ) : findings.length === 0 ? (
        <EmptyState icon={<CheckCircle2 size={22} />} title="Утечек по известным шаблонам не найдено">
          Ни одна из инспекций не нашла проблем в этом снапшоте. Проверьте рост классов на вкладке Snapshot Comparison:
          утечка в собственной структуре данных может не совпадать ни с одним шаблоном.
          <div className="mt-3"><RulesSummary runs={data?.inspections ?? []} /></div>
        </EmptyState>
      ) : (
        <div className="flex min-h-0 flex-1">
          <aside className="flex w-[300px] shrink-0 flex-col xl:w-[380px] border-r border-kraken-border bg-kraken-panel/60">
            <div className="min-h-0 flex-1 overflow-y-auto">
              <FindingList findings={visible} heapBytes={heapBytes} selectedId={selectedId} onSelect={setSelectedId} />
            </div>
            <RulesSummary runs={data?.inspections ?? []} />
          </aside>
          <div className="min-w-0 flex-1 overflow-y-auto p-5">
            {selected ? (
              <div className="mx-auto max-w-4xl">
                <FindingDetail key={selected.id} finding={selected} heapBytes={heapBytes}
                  openSnapshotId={chosen.heapOpen ? chosen.snapshotId : null} />
              </div>
            ) : (
              <EmptyState icon={<ShieldAlert size={22} />} title="Нет диагнозов в этой категории" />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

