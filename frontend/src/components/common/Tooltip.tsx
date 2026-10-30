import clsx from 'clsx';
import type { ReactNode } from 'react';

interface TooltipProps {
  content: ReactNode;
  children: ReactNode;
  side?: 'bottom' | 'top';
  align?: 'center' | 'start' | 'end';
  width?: string;
}

/** CSS-only tooltip (hover and keyboard focus), no portal and no extra dependency. */
export function Tooltip({ content, children, side = 'bottom', align = 'center', width = 'w-72' }: TooltipProps) {
  return (
    <span className="group relative inline-flex">
      {children}
      <span
        role="tooltip"
        className={clsx(
          'pointer-events-none absolute z-50 rounded-md border border-kraken-border bg-kraken-card px-3 py-2',
          'text-xs leading-relaxed text-kraken-text opacity-0 shadow-panel transition-opacity duration-150',
          'group-hover:opacity-100 group-focus-within:opacity-100',
          width,
          side === 'bottom' ? 'top-full mt-2' : 'bottom-full mb-2',
          align === 'center' && 'left-1/2 -translate-x-1/2',
          align === 'start' && 'left-0',
          align === 'end' && 'right-0',
        )}
      >
        {content}
      </span>
    </span>
  );
}
