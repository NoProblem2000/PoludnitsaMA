import clsx from 'clsx';
import { X } from 'lucide-react';
import { type ReactNode, useEffect, useId } from 'react';

interface ModalProps {
  open: boolean;
  title: ReactNode;
  icon?: ReactNode;
  width?: string;
  children: ReactNode;
  footer?: ReactNode;
  onClose(): void;
}

/** Centered dialog over a dimmed page; Escape and a click outside close it. */
export function Modal({ open, title, icon, width = 'max-w-2xl', children, footer, onClose }: ModalProps) {
  const titleId = useId();
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) {
    return null;
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={clsx('flex max-h-[90vh] w-full flex-col rounded-lg border border-kraken-border bg-kraken-panel shadow-panel', width)}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-2 border-b border-kraken-border px-5 py-3">
          {icon}
          <h2 id={titleId} className="min-w-0 flex-1 truncate text-sm font-semibold">
            {title}
          </h2>
          <button type="button" onClick={onClose} className="text-kraken-subtle hover:text-kraken-text" aria-label="Закрыть">
            <X size={16} />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-kraken-border px-5 py-3">{footer}</div>}
      </div>
    </div>
  );
}
