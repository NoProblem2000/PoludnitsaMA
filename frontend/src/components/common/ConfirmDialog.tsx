import clsx from 'clsx';
import { AlertTriangle } from 'lucide-react';
import { useEffect, type ReactNode } from 'react';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  confirmDisabled?: boolean;
  onConfirm(): void;
  onCancel(): void;
}

/** Modal confirmation for operations that pause the target JVM. Escape cancels. */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  danger = false,
  confirmDisabled = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCancel();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onCancel]);

  if (!open) {
    return null;
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={onCancel}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        className="w-full max-w-lg rounded-lg border border-kraken-border bg-kraken-panel shadow-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-2 border-b border-kraken-border px-5 py-3">
          <AlertTriangle size={18} className={danger ? 'text-accent-coral' : 'text-accent-amber'} />
          <h2 id="confirm-title" className="text-sm font-semibold">
            {title}
          </h2>
        </div>
        <div className="space-y-3 px-5 py-4 text-sm leading-relaxed text-kraken-muted">{children}</div>
        <div className="flex justify-end gap-2 border-t border-kraken-border px-5 py-3">
          <button
            type="button"
            onClick={onCancel}
            className="h-8 rounded-md border border-kraken-border px-3 text-xs text-kraken-text hover:bg-kraken-card"
          >
            Отмена
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={confirmDisabled}
            className={clsx(
              'h-8 rounded-md px-3 text-xs font-semibold text-kraken-bg disabled:cursor-not-allowed disabled:opacity-40',
              danger ? 'bg-accent-coral hover:bg-accent-coral/90' : 'bg-accent-cyan hover:bg-accent-cyan/90',
            )}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
