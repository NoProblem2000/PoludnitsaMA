import type { ReactNode } from 'react';

interface EmptyStateProps {
  icon: ReactNode;
  title: string;
  children?: ReactNode;
}

export function EmptyState({ icon, title, children }: EmptyStateProps) {
  return (
    <div className="flex h-full min-h-48 flex-col items-center justify-center gap-3 p-8 text-center">
      <div className="rounded-full border border-kraken-border bg-kraken-card p-3 text-accent-cyan">{icon}</div>
      <h3 className="text-sm font-semibold text-kraken-text">{title}</h3>
      {children && <div className="max-w-md text-xs leading-relaxed text-kraken-muted">{children}</div>}
    </div>
  );
}
