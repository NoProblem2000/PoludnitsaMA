import clsx from 'clsx';
import { Loader2, Upload } from 'lucide-react';
import { type DragEvent, type ReactNode, useRef, useState } from 'react';

interface FileDropZoneProps {
  title: string;
  hint?: ReactNode;
  accept?: string;
  busy?: boolean;
  disabled?: boolean;
  onFile(file: File): void;
}

/** Drag-and-drop target that also opens the file picker on click. */
export function FileDropZone({ title, hint, accept, busy = false, disabled = false, onFile }: FileDropZoneProps) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);

  const drop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    // keep the window-level report drop handler from opening the import dialog
    event.stopPropagation();
    setOver(false);
    const file = event.dataTransfer.files[0];
    if (file && !busy && !disabled) {
      onFile(file);
    }
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => !busy && !disabled && input.current?.click()}
      onKeyDown={(e) => e.key === 'Enter' && input.current?.click()}
      onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={drop}
      className={clsx(
        'flex cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border border-dashed px-4 py-4 text-center',
        'transition-colors',
        over ? 'border-accent-cyan bg-accent-cyan/10' : 'border-kraken-border bg-kraken-panel hover:border-accent-cyan/50',
        (busy || disabled) && 'cursor-not-allowed opacity-60',
      )}
    >
      {busy ? <Loader2 size={20} className="animate-spin text-accent-cyan" /> : <Upload size={20} className="text-accent-cyan" />}
      <div className="text-xs font-semibold text-kraken-text">{title}</div>
      {hint && <div className="text-2xs text-kraken-muted">{hint}</div>}
      <input
        ref={input}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (file) {
            onFile(file);
          }
        }}
      />
    </div>
  );
}
