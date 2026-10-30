import clsx from 'clsx';
import {
  Camera,
  FileJson,
  FileText,
  FolderOpen,
  Microscope,
  Radar,
  Save,
  Trash2,
  X,
} from 'lucide-react';
import { useCallback, useRef, useState } from 'react';
import type { DiskSpaceCheckDto } from '@/api/types';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { Tooltip } from '@/components/common/Tooltip';
import { ToolbarButton } from '@/components/common/ToolbarButton';
import { useClickOutside } from '@/hooks/useClickOutside';
import { useNow } from '@/hooks/useNow';
import { selectActiveProcess, useInspectorStore, type StreamState } from '@/store/useInspectorStore';
import { formatBytes, formatDateTime, formatUptime } from '@/utils/format';
import { ProcessSelector } from './ProcessSelector';

const STREAM_LABEL: Record<StreamState, string> = {
  idle: 'NO TARGET',
  connecting: 'CONNECTING…',
  live: 'LIVE STREAMING',
  reconnecting: 'RECONNECTING…',
  offline: 'OFFLINE REPORT',
};

function StreamIndicator() {
  const stream = useInspectorStore((s) => s.streamState);
  const target = useInspectorStore((s) => s.targetState);
  const message = useInspectorStore((s) => s.targetMessage);
  const lost = stream === 'live' && (target === 'RECONNECTING' || target === 'FAILED' || target === 'TERMINATED');
  const tone = lost ? 'coral' : stream === 'live' ? 'emerald' : stream === 'offline' ? 'violet'
    : stream === 'idle' ? 'subtle' : 'amber';
  const color = {
    coral: 'text-accent-coral',
    emerald: 'text-accent-emerald',
    violet: 'text-accent-violet',
    amber: 'text-accent-amber',
    subtle: 'text-kraken-subtle',
  }[tone];
  const dot = {
    coral: 'bg-accent-coral',
    emerald: 'bg-accent-emerald',
    violet: 'bg-accent-violet',
    amber: 'bg-accent-amber',
    subtle: 'bg-kraken-subtle',
  }[tone];
  return (
    <span className={clsx('flex items-center gap-1.5 text-2xs font-bold tracking-widest', color)} title={message ?? ''}>
      <span className="relative flex h-2 w-2">
        {stream === 'live' && !lost && <span className={clsx('absolute h-full w-full animate-ping rounded-full opacity-60', dot)} />}
        <span className={clsx('relative h-2 w-2 rounded-full', dot)} />
      </span>
      {lost ? target : STREAM_LABEL[stream]}
    </span>
  );
}

function Uptime() {
  const process = useInspectorStore(selectActiveProcess);
  const generatedAt = useInspectorStore((s) => s.offlineReport?.generatedAt);
  const now = useNow();
  if (!process?.startTimeEpochMs) {
    return null;
  }
  // Offline: the uptime the process had when the report was taken, not a clock that keeps running.
  const reference = generatedAt ? new Date(generatedAt).getTime() : now;
  return (
    <span className="font-mono text-xs text-kraken-muted" title={`Старт: ${formatDateTime(process.startTimeEpochMs)}`}>
      uptime <span className="text-kraken-text">{formatUptime(reference - process.startTimeEpochMs)}</span>
      {generatedAt && <span className="text-kraken-subtle"> (на момент отчёта)</span>}
    </span>
  );
}

function DumpModeSwitch() {
  const mode = useInspectorStore((s) => s.dumpMode);
  const setMode = useInspectorStore((s) => s.setDumpMode);
  const option = (value: 'safe' | 'exact', label: string) => (
    <button
      type="button"
      onClick={() => setMode(value)}
      className={clsx(
        'h-full px-2.5 text-xs transition-colors',
        mode === value
          ? value === 'safe' ? 'bg-accent-emerald/15 text-accent-emerald' : 'bg-accent-amber/15 text-accent-amber'
          : 'text-kraken-muted hover:text-kraken-text',
      )}
    >
      {label}
    </button>
  );
  return (
    <Tooltip
      width="w-96"
      content={
        <span className="block space-y-2">
          <span className="block">
            <b className="text-accent-emerald">Safe Dump (No Full GC)</b> —{' '}
            <code>dumpHeap(path, false)</code>: без Full GC, пауза только на запись дампа. В дампе есть и недостижимые
            объекты. Безопасно для heartbeat Hazelcast-клиента.
          </span>
          <span className="block">
            <b className="text-accent-amber">Exact Dump (Live Objects Only)</b> —{' '}
            <code>dumpHeap(path, true)</code>: сначала Full GC, в дампе только живые объекты (точнее для diff), но
            пауза дольше и может оборвать соединение с Hazelcast по таймауту.
          </span>
        </span>
      }
    >
      <span className="flex h-8 overflow-hidden rounded-md border border-kraken-border">
        {option('safe', 'Safe Dump (No Full GC)')}
        <span className="w-px bg-kraken-border" />
        {option('exact', 'Exact Dump (Live Objects Only)')}
      </span>
    </Tooltip>
  );
}

function ExportMenu({ disabled }: { disabled: boolean }) {
  const exportReport = useInspectorStore((s) => s.exportReport);
  const busy = useInspectorStore((s) => s.busy.export);
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(root, open, close);
  const pick = (format: 'JSON' | 'HTML') => {
    setOpen(false);
    void exportReport(format);
  };
  return (
    <div ref={root} className="relative">
      <ToolbarButton
        icon={<Save size={14} />}
        label="Экспорт JSON/HTML"
        busy={busy}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
      />
      {open && (
        <div className="absolute right-0 top-full z-40 mt-1 w-64 rounded-lg border border-kraken-border bg-kraken-panel py-1 shadow-panel">
          <button type="button" onClick={() => pick('JSON')} className="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-kraken-card">
            <FileJson size={16} className="mt-0.5 text-accent-cyan" />
            <span>
              <span className="block text-sm">JSON (.json.gz)</span>
              <span className="block text-2xs text-kraken-muted">Открывается в этом UI на любом компьютере</span>
            </span>
          </button>
          <button type="button" onClick={() => pick('HTML')} className="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-kraken-card">
            <FileText size={16} className="mt-0.5 text-accent-violet" />
            <span>
              <span className="block text-sm">HTML-отчёт</span>
              <span className="block text-2xs text-kraken-muted">Автономный файл для коллег</span>
            </span>
          </button>
        </div>
      )}
    </div>
  );
}

/** GitKraken-style top bar: process, stream state, dump mode and actions. */
export function TopToolbar() {
  const mode = useInspectorStore((s) => s.mode);
  const activePid = useInspectorStore((s) => s.activePid);
  const busy = useInspectorStore((s) => s.busy);
  const dumpMode = useInspectorStore((s) => s.dumpMode);
  const report = useInspectorStore((s) => s.offlineReport);
  const quickHistogram = useInspectorStore((s) => s.quickHistogram);
  const checkDiskSpace = useInspectorStore((s) => s.checkDiskSpace);
  const deepDump = useInspectorStore((s) => s.deepDump);
  const forceGc = useInspectorStore((s) => s.forceGc);
  const openReportFile = useInspectorStore((s) => s.openReportFile);
  const closeOfflineReport = useInspectorStore((s) => s.closeOfflineReport);
  const fileInput = useRef<HTMLInputElement>(null);
  const [dumpDialog, setDumpDialog] = useState(false);
  const [disk, setDisk] = useState<DiskSpaceCheckDto | null>(null);
  const [gcDialog, setGcDialog] = useState(false);

  const live = mode === 'live';
  const canAct = live && activePid != null;

  const openDumpDialog = async () => {
    setDisk(null);
    setDumpDialog(true);
    setDisk(await checkDiskSpace());
  };

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-kraken-border bg-kraken-panel px-3">
      <div className="flex items-center gap-2 pr-2">
        <Radar size={20} className="text-accent-cyan" />
        <span className="hidden text-sm font-semibold xl:inline">JVM Leak Inspector</span>
      </div>

      {live ? (
        <ProcessSelector />
      ) : (
        <div className="flex h-9 min-w-72 items-center gap-2 rounded-md border border-accent-violet/50 bg-accent-violet/10 px-3">
          <FolderOpen size={16} className="text-accent-violet" />
          <span className="truncate text-sm">{report?.title}</span>
          <span className="shrink-0 text-2xs text-kraken-muted">{report ? formatDateTime(report.generatedAt) : ''}</span>
        </div>
      )}

      <div className="flex flex-col justify-center gap-0.5">
        <StreamIndicator />
        <Uptime />
      </div>

      <div className="ml-auto flex items-center gap-2">
        {live && <DumpModeSwitch />}
        <ToolbarButton
          icon={<Camera size={14} />}
          label="Quick Histogram"
          title="GC.class_histogram -all: без Full GC, пауза — миллисекунды"
          busy={busy.histogram}
          disabled={!canAct}
          onClick={() => void quickHistogram()}
        />
        <ToolbarButton
          icon={<Microscope size={14} />}
          label="Deep Heap Dump"
          tone="primary"
          title="Heap dump + retained size, dominator tree, пути до GC Root и эвристики"
          busy={busy.deep}
          disabled={!canAct}
          onClick={() => void openDumpDialog()}
        />
        <ToolbarButton
          icon={<Trash2 size={14} />}
          label="Force GC"
          tone="danger"
          busy={busy.gc}
          disabled={!canAct}
          onClick={() => setGcDialog(true)}
        />
        <span className="mx-1 h-6 w-px bg-kraken-border" />
        <ExportMenu disabled={!live} />
        <ToolbarButton
          icon={<FolderOpen size={14} />}
          label="Открыть отчёт"
          busy={busy.report}
          onClick={() => fileInput.current?.click()}
        />
        {!live && (
          <ToolbarButton icon={<X size={14} />} label="Закрыть отчёт" onClick={closeOfflineReport} />
        )}
        <input
          ref={fileInput}
          type="file"
          accept=".json,.gz,application/json,application/gzip"
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) {
              void openReportFile(file);
            }
          }}
        />
      </div>

      <ConfirmDialog
        open={dumpDialog}
        title={dumpMode === 'exact' ? 'Exact Dump: Full GC + heap dump' : 'Safe Dump: heap dump без Full GC'}
        confirmLabel="Снять дамп"
        confirmDisabled={disk == null || !disk.sufficient}
        onCancel={() => setDumpDialog(false)}
        onConfirm={() => {
          setDumpDialog(false);
          void deepDump();
        }}
      >
        <p>
          Запись дампа — это <b className="text-kraken-text">stop-the-world пауза</b> целевой JVM на всё время записи
          (примерно пропорционально занятой куче).
          {dumpMode === 'exact'
            ? ' Перед записью выполнится Full GC: пауза дольше, Hazelcast-клиент может потерять соединение по таймауту heartbeat.'
            : ' Full GC не вызывается: в дампе будут и недостижимые объекты.'}
        </p>
        {disk == null ? (
          <p>Проверяем свободное место…</p>
        ) : (
          <p className={disk.sufficient ? 'text-accent-emerald' : 'text-accent-coral'}>
            {disk.sufficient ? 'Места достаточно' : 'Недостаточно места'}: нужно {formatBytes(disk.requiredBytes)} (
            {disk.factor} × {formatBytes(disk.heapUsedBytes)} used heap), свободно {formatBytes(disk.usableBytes)} в{' '}
            <code className="text-kraken-text">{disk.directory}</code>.
          </p>
        )}
        <p>После анализа сырой .hprof и индексы NetBeans удаляются с диска автоматически.</p>
      </ConfirmDialog>

      <ConfirmDialog
        open={gcDialog}
        title="Force GC на целевой JVM"
        confirmLabel="Выполнить GC"
        danger
        onCancel={() => setGcDialog(false)}
        onConfirm={() => {
          setGcDialog(false);
          void forceGc();
        }}
      >
        <p>
          <code className="text-kraken-text">MemoryMXBean.gc()</code> на большинстве конфигураций — это{' '}
          <b className="text-kraken-text">stop-the-world Full GC</b>: на больших кучах пауза длится секунды, и
          Hazelcast-клиент может разорвать соединение с кластером.
        </p>
        <p>
          Если у процесса стоит <code>-XX:+DisableExplicitGC</code>, вызов будет проигнорирован; с{' '}
          <code>-XX:+ExplicitGCInvokesConcurrent</code> выполнится конкурентный цикл.
        </p>
      </ConfirmDialog>
    </header>
  );
}
