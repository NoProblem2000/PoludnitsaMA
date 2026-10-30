import { GitCommit } from 'lucide-react';
import { useEffect, useState } from 'react';
import { api } from '@/api/endpoints';
import type { AboutDto } from '@/api/types';
import { AboutLicenseModal, LicenseBadge } from '@/features/about/AboutLicenseModal';
import { useInspectorStore } from '@/store/useInspectorStore';

/** Bottom status bar: mode on the left; version, commit and the license badge (opens About) on the right. */
export function StatusBar() {
  const mode = useInspectorStore((s) => s.mode);
  const processes = useInspectorStore((s) => s.processes.length);
  const [about, setAbout] = useState<AboutDto | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let current = true;
    api.about.get().then((value) => current && setAbout(value), () => undefined);
    return () => {
      current = false;
    };
  }, []);

  return (
    <div className="flex h-6 shrink-0 items-center gap-3 border-t border-kraken-border bg-kraken-panel px-3 text-2xs text-kraken-subtle">
      <span>{mode === 'offline' ? 'Офлайн-отчёт' : `${processes} JVM на хосте`}</span>
      <span title="UI и API слушают только локальный интерфейс">только 127.0.0.1</span>
      <span className="ml-auto flex items-center gap-2">
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="flex items-center gap-1.5 font-mono hover:text-kraken-text"
          title="О программе"
        >
          JVM Leak Inspector v{about?.version ?? '…'}
          {about?.gitCommitId && (
            <span className="flex items-center gap-0.5 text-kraken-subtle">
              <GitCommit size={11} />
              {about.gitCommitId}
            </span>
          )}
        </button>
        <LicenseBadge onClick={() => setOpen(true)} />
      </span>
      <AboutLicenseModal open={open} about={about} onClose={() => setOpen(false)} />
    </div>
  );
}
