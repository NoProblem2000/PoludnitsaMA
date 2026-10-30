import { AlertTriangle, Cpu, FolderOpen, Info } from 'lucide-react';
import { EmptyState } from '@/components/common/EmptyState';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { GENERATION_COLOR } from '@/utils/categories';
import { formatDateTime } from '@/utils/format';
import { LeakRadarTable } from './LeakRadarTable';
import { MemoryPoolsChart } from './MemoryPoolsChart';
import { StatCards } from './StatCards';

function ChartLegendNote() {
  const dot = (color: string) => <span className="inline-block h-2 w-2 rounded-full" style={{ backgroundColor: color }} />;
  return (
    <span className="flex items-center gap-3 text-2xs text-kraken-subtle">
      <span className="flex items-center gap-1">{dot(GENERATION_COLOR.YOUNG)} young GC</span>
      <span className="flex items-center gap-1">{dot(GENERATION_COLOR.OLD)} old/full GC</span>
      <span className="flex items-center gap-1">{dot(GENERATION_COLOR.CONCURRENT)} concurrent</span>
      <span className="flex items-center gap-1">
        <span className="inline-block h-0 w-4 border-t-2 border-dashed" style={{ borderColor: palette.coral }} />
        old gen после GC
      </span>
    </span>
  );
}

/** Screen 1: memory pools, GC pauses and the Live Leak Radar of the selected JVM (or of an offline report). */
export function LiveDashboardView() {
  const mode = useInspectorStore((s) => s.mode);
  const activePid = useInspectorStore((s) => s.activePid);
  const targetState = useInspectorStore((s) => s.targetState);
  const targetMessage = useInspectorStore((s) => s.targetMessage);
  const report = useInspectorStore((s) => s.offlineReport);

  if (mode === 'live' && activePid == null) {
    return (
      <EmptyState icon={<Cpu size={22} />} title="Выберите процесс JVM">
        Откройте селектор процессов в тулбаре: инспектор подключится к выбранной JVM через Attach API и её
        MBeanServerConnection и начнёт стримить пулы памяти, GC и Leak Radar раз в секунду. Или откройте
        сохранённый отчёт кнопкой «Открыть отчёт».
      </EmptyState>
    );
  }

  const troubled = mode === 'live' && (targetState === 'FAILED' || targetState === 'RECONNECTING' || targetState === 'TERMINATED');

  return (
    <div className="flex flex-col gap-3 p-4">
      {mode === 'offline' && report && (
        <div className="flex items-center gap-2 rounded-md border border-accent-violet/40 bg-accent-violet/10 px-3 py-2 text-xs">
          <FolderOpen size={14} className="text-accent-violet" />
          <span>
            Offline Report Viewer: <b>{report.title}</b> · сформирован {formatDateTime(report.generatedAt)} на{' '}
            {report.hostName}. Данные из файла, подключения к JVM нет.
          </span>
        </div>
      )}
      {troubled && (
        <div className="flex items-start gap-2 rounded-md border border-accent-coral/40 bg-accent-coral/10 px-3 py-2 text-xs">
          <AlertTriangle size={14} className="mt-0.5 shrink-0 text-accent-coral" />
          <span>
            <b className="text-accent-coral">{targetState}</b>
            {targetMessage ? `: ${targetMessage}` : ''}
          </span>
        </div>
      )}

      <StatCards />

      <section className="rounded-lg border border-kraken-border bg-kraken-panel">
        <div className="flex items-center justify-between border-b border-kraken-border px-4 py-2.5">
          <h2 className="text-sm font-semibold">Memory Pools & GC Timeline</h2>
          <ChartLegendNote />
        </div>
        <div className="px-2 pt-2">
          <MemoryPoolsChart />
        </div>
        {mode === 'live' && (
          <div className="flex items-center gap-1.5 px-4 pb-2 text-2xs text-kraken-subtle">
            <Info size={11} />
            Последние 300 секунд. Колесо мыши — масштаб. Растущая пунктирная линия «Old Gen после GC» —
            главный признак утечки.
          </div>
        )}
      </section>

      <section className="overflow-hidden rounded-lg border border-kraken-border bg-kraken-card/40">
        <LeakRadarTable />
      </section>
    </div>
  );
}
