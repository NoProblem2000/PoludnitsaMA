import clsx from 'clsx';
import type { ReactNode } from 'react';
import { withAlpha } from '@/theme/palette';

interface BadgeProps {
  color: string;
  children: ReactNode;
  solid?: boolean;
  className?: string;
  title?: string;
}

/** Small outlined (or solid) label in one of the palette colors. */
export function Badge({ color, children, solid = false, className, title }: BadgeProps) {
  return (
    <span
      title={title}
      className={clsx(
        'inline-flex items-center gap-1 whitespace-nowrap rounded px-1.5 py-px text-2xs font-semibold uppercase tracking-wide',
        className,
      )}
      style={{
        color: solid ? '#1b1d23' : color,
        backgroundColor: solid ? color : withAlpha(color, 0.12),
        border: `1px solid ${withAlpha(color, solid ? 1 : 0.45)}`,
      }}
    >
      {children}
    </span>
  );
}
