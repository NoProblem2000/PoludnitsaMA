import clsx from 'clsx';
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-react';
import { useInspectorStore, type ToastTone } from '@/store/useInspectorStore';

const ICON: Record<ToastTone, typeof Info> = {
  info: Info,
  success: CheckCircle2,
  warning: AlertTriangle,
  error: XCircle,
};

const TONE: Record<ToastTone, string> = {
  info: 'border-l-accent-cyan text-accent-cyan',
  success: 'border-l-accent-emerald text-accent-emerald',
  warning: 'border-l-accent-amber text-accent-amber',
  error: 'border-l-accent-coral text-accent-coral',
};

export function ToastStack() {
  const toasts = useInspectorStore((s) => s.toasts);
  const dismiss = useInspectorStore((s) => s.dismissToast);
  return (
    <div className="pointer-events-none fixed bottom-28 right-4 z-50 flex w-96 flex-col gap-2" aria-live="polite">
      {toasts.map((toast) => {
        const Icon = ICON[toast.tone];
        return (
          <div
            key={toast.id}
            className={clsx(
              'pointer-events-auto flex gap-2 rounded-md border border-l-4 border-kraken-border bg-kraken-card px-3 py-2 shadow-panel',
              TONE[toast.tone],
            )}
          >
            <Icon size={16} className="mt-0.5 shrink-0" />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-kraken-text">{toast.title}</div>
              {toast.message && <div className="mt-0.5 break-words text-xs text-kraken-muted">{toast.message}</div>}
            </div>
            <button type="button" onClick={() => dismiss(toast.id)} className="shrink-0 text-kraken-subtle hover:text-kraken-text">
              <X size={14} />
            </button>
          </div>
        );
      })}
    </div>
  );
}
