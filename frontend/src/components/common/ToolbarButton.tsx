import clsx from 'clsx';
import { Loader2 } from 'lucide-react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Tone = 'default' | 'primary' | 'danger';

interface ToolbarButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon: ReactNode;
  label: string;
  busy?: boolean;
  tone?: Tone;
}

const TONES: Record<Tone, string> = {
  default: 'border-kraken-border text-kraken-text hover:border-accent-cyan/60 hover:text-accent-cyan',
  primary: 'border-accent-cyan/50 bg-accent-cyan/10 text-accent-cyan hover:bg-accent-cyan/20',
  danger: 'border-kraken-border text-kraken-text hover:border-accent-coral/70 hover:text-accent-coral',
};

export function ToolbarButton({ icon, label, busy = false, tone = 'default', className, disabled, ...rest }: ToolbarButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled || busy}
      className={clsx(
        'inline-flex h-8 items-center gap-1.5 whitespace-nowrap rounded-md border px-2.5 text-xs font-medium',
        'transition-colors disabled:cursor-not-allowed disabled:opacity-40',
        TONES[tone],
        className,
      )}
      {...rest}
    >
      {busy ? <Loader2 size={14} className="animate-spin" /> : icon}
      <span>{label}</span>
    </button>
  );
}
