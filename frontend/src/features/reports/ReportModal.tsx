import clsx from 'clsx';
import { FileJson, FileText, FolderOpen, Loader2, UploadCloud } from 'lucide-react';
import { type DragEvent, type ReactNode, useEffect, useRef, useState } from 'react';
import { Modal } from '@/components/common/Modal';
import { useSnapshotNumber } from '@/components/common/SnapshotSelect';
import { useComparePair, useInspectorStore } from '@/store/useInspectorStore';
import { formatBytes } from '@/utils/format';

const ACCEPT = '.json,.gz,application/json,application/gzip';

function isReportFile(file: File): boolean {
  return /\.(json|json\.gz|gz)$/i.test(file.name) || file.type === 'application/json' || file.type === 'application/gzip';
}

function DropZone({ busy, onFile }: { busy: boolean; onFile(file: File): void }) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const [rejected, setRejected] = useState<string | null>(null);

  const take = (file: File | undefined) => {
    if (!file) {
      return;
    }
    if (!isReportFile(file)) {
      setRejected(`${file.name}: нужен отчёт .json или .json.gz, экспортированный этим инспектором`);
      return;
    }
    setRejected(null);
    onFile(file);
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    take(event.dataTransfer.files[0]);
  };

  return (
    <div>
      <button
        type="button"
        disabled={busy}
        onClick={() => input.current?.click()}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = 'copy';
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={clsx(
          'flex w-full flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-6 py-8 text-center transition-colors',
          over ? 'border-accent-cyan bg-accent-cyan/10 shadow-glow-cyan' : 'border-kraken-border hover:border-accent-cyan/60 hover:bg-kraken-card/60',
          busy && 'cursor-wait opacity-70',
        )}
      >
        {busy ? <Loader2 size={28} className="animate-spin text-accent-cyan" />
          : <UploadCloud size={28} className={over ? 'text-accent-cyan' : 'text-kraken-muted'} />}
        <span className="text-sm text-kraken-text">
          {busy ? 'Читаем отчёт…' : over ? 'Отпустите, чтобы открыть' : 'Перетащите сюда сохранённый отчёт'}
        </span>
        <span className="text-2xs text-kraken-subtle">
          .json или .json.gz до 512 МБ · или нажмите, чтобы выбрать файл · отчёт читается в браузере, бэкенд не нужен
        </span>
      </button>
      {rejected && <p className="mt-2 text-2xs text-accent-coral">{rejected}</p>}
      <input
        ref={input}
        type="file"
        accept={ACCEPT}
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          take(file);
        }}
      />
    </div>
  );
}

function ExportButton({ icon, title, hint, disabled, onClick }: {
  icon: ReactNode;
  title: string;
  hint: string;
  disabled?: boolean;
  onClick(): void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="flex flex-1 items-start gap-3 rounded-lg border border-kraken-border bg-kraken-card px-4 py-3 text-left transition-colors hover:border-accent-cyan/60 disabled:cursor-not-allowed disabled:opacity-40"
    >
      <span className="mt-0.5">{icon}</span>
      <span>
        <span className="block text-sm font-medium">{title}</span>
        <span className="block text-2xs leading-relaxed text-kraken-muted">{hint}</span>
      </span>
    </button>
  );
}

/** Export of the current analysis (JSON for this UI, HTML for colleagues) and import of a saved report by drop. */
export function ReportModal() {
  const open = useInspectorStore((s) => s.reportModalOpen);
  const setOpen = useInspectorStore((s) => s.setReportModalOpen);
  const mode = useInspectorStore((s) => s.mode);
  const busy = useInspectorStore((s) => s.busy);
  const report = useInspectorStore((s) => s.offlineReport);
  const reportFile = useInspectorStore((s) => s.offlineReportFile);
  const snapshots = useInspectorStore((s) => s.snapshots);
  const activePid = useInspectorStore((s) => s.activePid);
  const pair = useComparePair();
  const openReportFile = useInspectorStore((s) => s.openReportFile);
  const exportReport = useInspectorStore((s) => s.exportReport);
  const exportOfflineReport = useInspectorStore((s) => s.exportOfflineReport);
  const number = useSnapshotNumber();
  const [title, setTitle] = useState('');
  const [withHistory, setWithHistory] = useState(true);
  const [withPair, setWithPair] = useState(true);

  useEffect(() => {
    if (open) {
      setTitle('');
    }
  }, [open]);

  const close = () => setOpen(false);
  const live = mode === 'live';
  const finished = snapshots.filter((s) => s.status === 'READY').length;

  const runExport = async (format: 'JSON' | 'HTML') => {
    if (live) {
      await exportReport(format, {
        title,
        includeMetricsHistory: withHistory,
        diffs: withPair && pair ? [{ baselineId: pair[0].snapshotId, currentId: pair[1].snapshotId }] : [],
      });
    } else {
      await exportOfflineReport(format);
    }
  };

  return (
    <Modal open={open} onClose={close} width="max-w-2xl" icon={<FolderOpen size={16} className="text-accent-cyan" />} title="Отчёты: экспорт и импорт">
      <div className="space-y-6">
        <section className="space-y-2">
          <h3 className="text-2xs font-semibold uppercase tracking-wider text-kraken-muted">Открыть сохранённый отчёт</h3>
          <DropZone
            busy={busy.report}
            onFile={(file) => void openReportFile(file).then((opened) => opened && close())}
          />
        </section>

        <section className="space-y-3">
          <h3 className="text-2xs font-semibold uppercase tracking-wider text-kraken-muted">
            {live ? 'Сохранить текущий анализ' : 'Сохранить открытый отчёт'}
          </h3>
          {live ? (
            <>
              <div className="grid grid-cols-[1fr_auto] items-center gap-3">
                <input
                  value={title}
                  onChange={(event) => setTitle(event.target.value)}
                  maxLength={200}
                  placeholder="Название отчёта (по умолчанию — имя процесса и время)"
                  className="h-8 rounded-md border border-kraken-border bg-kraken-bg px-2 text-xs text-kraken-text outline-none placeholder:text-kraken-subtle focus:border-accent-cyan/70"
                />
                <span className="text-2xs text-kraken-subtle">
                  {activePid == null ? 'все процессы' : `PID ${activePid}`} · {finished} снапшотов
                </span>
              </div>
              <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-kraken-muted">
                <label className="flex items-center gap-2">
                  <input type="checkbox" checked={withHistory} onChange={(event) => setWithHistory(event.target.checked)} className="accent-[#00e5ff]" />
                  История live-метрик (график пулов)
                </label>
                <label className={clsx('flex items-center gap-2', !pair && 'opacity-40')}>
                  <input type="checkbox" checked={withPair && pair != null} disabled={!pair} onChange={(event) => setWithPair(event.target.checked)} className="accent-[#00e5ff]" />
                  {pair ? `Diff #${number(pair[0].snapshotId)} → #${number(pair[1].snapshotId)}` : 'Diff выбранной пары (не выбрана — старший с новейшим)'}
                </label>
              </div>
            </>
          ) : (
            <p className="text-xs text-kraken-muted">
              {report?.title} — {report?.snapshots.length} снапшотов{reportFile ? `, файл ${reportFile.name} (${formatBytes(reportFile.size)})` : ''}.
              HTML собирает бэкенд: снапшоты отчёта будут импортированы в его сессию.
            </p>
          )}
          <div className="flex gap-3">
            <ExportButton
              icon={busy.export ? <Loader2 size={18} className="animate-spin text-accent-cyan" /> : <FileJson size={18} className="text-accent-cyan" />}
              title={live ? 'JSON (.json.gz)' : 'JSON (.json)'}
              hint="Полный анализ: классы, доминаторы, пути до GC Root, диагнозы. Открывается в этом UI на любом компьютере."
              disabled={busy.export || (live && finished === 0 && activePid == null)}
              onClick={() => void runExport('JSON')}
            />
            <ExportButton
              icon={busy.export ? <Loader2 size={18} className="animate-spin text-accent-violet" /> : <FileText size={18} className="text-accent-violet" />}
              title="HTML-отчёт"
              hint="Автономная страница без скриптов для коллег, тикета или ревью."
              disabled={busy.export || (live ? finished === 0 && activePid == null : !reportFile)}
              onClick={() => void runExport('HTML')}
            />
          </div>
          {live && finished === 0 && activePid == null && (
            <p className="text-2xs text-accent-amber">Выберите процесс или снимите снапшот: экспортировать пока нечего.</p>
          )}
        </section>
      </div>
    </Modal>
  );
}
