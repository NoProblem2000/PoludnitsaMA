import clsx from 'clsx';
import { Check, Copy } from 'lucide-react';
import { type ReactNode, useEffect, useState } from 'react';
import { palette } from '@/theme/palette';

interface CodeBlockProps {
  code: string;
  title?: string;
  className?: string;
}

// Enough of Java / XML / CLI to make a fix snippet readable; not a parser.
const TOKEN = new RegExp(
  [
    String.raw`(?<comment>//[^\n]*|/\*[\s\S]*?\*/|<!--[\s\S]*?-->|^\s*#[^\n]*)`,
    String.raw`(?<string>"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)')`,
    String.raw`(?<annotation>@[A-Za-z_][\w.]*)`,
    String.raw`(?<keyword>\b(?:public|private|protected|static|final|class|interface|enum|record|void|new|return|if|else|for|while|try|catch|finally|throw|throws|import|package|extends|implements|this|null|true|false|var|synchronized|volatile|transient)\b)`,
    String.raw`(?<tag></?[A-Za-z][\w:-]*|/?>)`,
    String.raw`(?<number>\b\d[\d_]*(?:\.\d+)?[LlFfDd]?\b)`,
  ].join('|'),
  'gm',
);

const TOKEN_COLOR: Record<string, string> = {
  comment: palette.subtle,
  string: palette.emerald,
  annotation: palette.amber,
  keyword: palette.violet,
  tag: palette.cyan,
  number: palette.amber,
};

function highlight(code: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let last = 0;
  for (const match of code.matchAll(TOKEN)) {
    const start = match.index ?? 0;
    if (start > last) {
      nodes.push(code.slice(last, start));
    }
    const kind = Object.entries(match.groups ?? {}).find(([, value]) => value !== undefined)?.[0] ?? 'comment';
    nodes.push(
      <span key={start} style={{ color: TOKEN_COLOR[kind], fontStyle: kind === 'comment' ? 'italic' : undefined }}>
        {match[0]}
      </span>,
    );
    last = start + match[0].length;
  }
  nodes.push(code.slice(last));
  return nodes;
}

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // http on a non-localhost name has no async clipboard
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const done = document.execCommand('copy');
    area.remove();
    return done;
  }
}

export function CopyButton({ text, label = 'Копировать' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) {
      return undefined;
    }
    const timer = window.setTimeout(() => setCopied(false), 1_600);
    return () => window.clearTimeout(timer);
  }, [copied]);
  return (
    <button
      type="button"
      onClick={() => void copyText(text).then(setCopied)}
      className={clsx(
        'inline-flex h-6 items-center gap-1 rounded border px-2 text-2xs transition-colors',
        copied ? 'border-accent-emerald/60 text-accent-emerald'
          : 'border-kraken-border text-kraken-muted hover:border-accent-cyan/60 hover:text-accent-cyan',
      )}
    >
      {copied ? <Check size={11} /> : <Copy size={11} />}
      {copied ? 'Скопировано' : label}
    </button>
  );
}

/** Dark code panel with light highlighting and a copy button. */
export function CodeBlock({ code, title = 'Пример исправления', className }: CodeBlockProps) {
  return (
    <div className={clsx('overflow-hidden rounded-md border border-kraken-border bg-[#14161b]', className)}>
      <div className="flex items-center justify-between border-b border-kraken-border bg-kraken-bg px-3 py-1.5">
        <span className="flex items-center gap-1.5 text-2xs font-semibold uppercase tracking-wider text-kraken-muted">
          <span className="h-2 w-2 rounded-full bg-accent-coral/80" />
          <span className="h-2 w-2 rounded-full bg-accent-amber/80" />
          <span className="h-2 w-2 rounded-full bg-accent-emerald/80" />
          <span className="ml-1.5">{title}</span>
        </span>
        <CopyButton text={code} />
      </div>
      <pre className="max-h-[28rem] overflow-auto p-3 font-mono text-xs leading-relaxed text-kraken-text">
        <code>{highlight(code)}</code>
      </pre>
    </div>
  );
}
