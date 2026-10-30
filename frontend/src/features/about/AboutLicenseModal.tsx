import clsx from 'clsx';
import { ChevronDown, ChevronRight, ExternalLink, Info, Loader2, Radar, Scale } from 'lucide-react';
import { type ReactNode, useEffect, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { AboutDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { CopyButton } from '@/components/common/CodeBlock';
import { Modal } from '@/components/common/Modal';
import { palette } from '@/theme/palette';
import { formatDateTime } from '@/utils/format';

interface Component {
  name: string;
  license: string;
  url: string;
  part: 'backend' | 'ui';
}

// Keep in step with NOTICE in the repository root.
const COMPONENTS: Component[] = [
  { name: 'Spring Boot 3.5', license: 'Apache-2.0', url: 'https://spring.io/projects/spring-boot', part: 'backend' },
  { name: 'Apache NetBeans Profiler API', license: 'Apache-2.0', url: 'https://netbeans.apache.org/', part: 'backend' },
  { name: 'Apache Tomcat (embedded)', license: 'Apache-2.0', url: 'https://tomcat.apache.org/', part: 'backend' },
  { name: 'Jackson', license: 'Apache-2.0', url: 'https://github.com/FasterXML/jackson', part: 'backend' },
  { name: 'Hibernate Validator', license: 'Apache-2.0', url: 'https://hibernate.org/validator/', part: 'backend' },
  { name: 'Apache ECharts 5', license: 'Apache-2.0', url: 'https://echarts.apache.org/', part: 'ui' },
  { name: 'ZRender', license: 'BSD-3-Clause', url: 'https://github.com/ecomfe/zrender', part: 'ui' },
  { name: 'React 18', license: 'MIT', url: 'https://react.dev/', part: 'ui' },
  { name: 'Zustand', license: 'MIT', url: 'https://github.com/pmndrs/zustand', part: 'ui' },
  { name: 'TanStack Virtual', license: 'MIT', url: 'https://tanstack.com/virtual', part: 'ui' },
  { name: 'Lucide', license: 'ISC', url: 'https://lucide.dev/', part: 'ui' },
  { name: 'clsx', license: 'MIT', url: 'https://github.com/lukeed/clsx', part: 'ui' },
  { name: 'Tailwind CSS', license: 'MIT', url: 'https://tailwindcss.com/', part: 'ui' },
];

const LICENSE_COLOR: Record<string, string> = {
  'Apache-2.0': palette.cyan,
  MIT: palette.emerald,
  ISC: palette.emerald,
  'BSD-3-Clause': palette.violet,
};

/** "Apache 2.0 License" badge used in the status bar and the dialog. */
export function LicenseBadge({ onClick }: { onClick?(): void }) {
  const content = (
    <>
      <Scale size={10} />
      Apache 2.0 License
    </>
  );
  return onClick ? (
    <button type="button" onClick={onClick} className="rounded transition-opacity hover:opacity-80" title="Лицензия и сторонние компоненты">
      <Badge color={palette.cyan}>{content}</Badge>
    </button>
  ) : (
    <Badge color={palette.cyan}>{content}</Badge>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline gap-3 py-1">
      <span className="w-32 shrink-0 text-2xs uppercase tracking-wider text-kraken-subtle">{label}</span>
      <span className="min-w-0 break-all font-mono text-xs text-kraken-text">{children}</span>
    </div>
  );
}

function LegalText({ title, load }: { title: string; load(): Promise<string> }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<{ text: string | null; error: string | null }>({ text: null, error: null });
  useEffect(() => {
    if (!open || state.text != null || state.error != null) {
      return;
    }
    let current = true;
    load().then(
      (text) => current && setState({ text, error: null }),
      (error: unknown) => current && setState({ text: null, error: errorMessage(error) }),
    );
    return () => {
      current = false;
    };
  }, [open, load, state.text, state.error]);

  return (
    <div className="rounded-md border border-kraken-border">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs text-kraken-muted hover:text-kraken-text"
      >
        {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        {title}
        {open && state.text && <span className="ml-auto"><CopyButton text={state.text} /></span>}
      </button>
      {open && (
        <div className="border-t border-kraken-border">
          {state.text != null ? (
            <pre className="max-h-72 overflow-auto bg-kraken-bg p-3 font-mono text-2xs leading-relaxed text-kraken-muted">{state.text}</pre>
          ) : state.error ? (
            <p className="p-3 text-2xs text-accent-amber">{state.error}</p>
          ) : (
            <p className="flex items-center gap-2 p-3 text-2xs text-kraken-muted"><Loader2 size={12} className="animate-spin" /> Загрузка…</p>
          )}
        </div>
      )}
    </div>
  );
}

interface AboutLicenseModalProps {
  open: boolean;
  about: AboutDto | null;
  onClose(): void;
}

/** Version, build, product license and the bundled open-source components. */
export function AboutLicenseModal({ open, about, onClose }: AboutLicenseModalProps) {
  return (
    <Modal open={open} onClose={onClose} width="max-w-2xl" icon={<Info size={16} className="text-accent-cyan" />} title="О программе и лицензии">
      <div className="space-y-5">
        <div className="flex items-center gap-4 rounded-lg border border-kraken-border bg-kraken-bg p-4">
          <div className="rounded-full border border-accent-cyan/40 bg-accent-cyan/10 p-3 text-accent-cyan">
            <Radar size={26} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-base font-semibold">{about?.name ?? 'JVM Memory & Leak Inspector'}</div>
            <div className="mt-0.5 text-xs text-kraken-muted">
              Анализ памяти и утечек Java 17+ / WildFly / Hazelcast на том же хосте
            </div>
          </div>
          <div className="text-right">
            <div className="font-mono text-lg text-accent-cyan">v{about?.version ?? '—'}</div>
            <LicenseBadge />
          </div>
        </div>

        <section>
          <h3 className="mb-1 text-2xs font-semibold uppercase tracking-wider text-kraken-muted">Сборка</h3>
          <div className="rounded-md border border-kraken-border bg-kraken-panel px-3 py-1">
            <Row label="Версия">{about?.version ?? '—'}</Row>
            <Row label="Собрано">{about?.buildTime ? formatDateTime(about.buildTime) : 'dev-сборка (без -Prelease)'}</Row>
            <Row label="Ветка / коммит">
              {about?.gitCommitId
                ? <>{about.gitBranch ?? '—'} @ {about.gitCommitId}{about.gitDirty && <span className="text-accent-amber"> (dirty)</span>}</>
                : '—'}
            </Row>
            {about?.gitCommitTime && <Row label="Время коммита">{formatDateTime(about.gitCommitTime)}</Row>}
            <Row label="JVM анализатора">{about?.runtimeJava ?? '—'}</Row>
          </div>
        </section>

        <section className="space-y-2">
          <h3 className="text-2xs font-semibold uppercase tracking-wider text-kraken-muted">Лицензия</h3>
          <p className="text-xs leading-relaxed text-kraken-muted">
            Распространяется по лицензии <b className="text-kraken-text">Apache License, Version 2.0</b>: можно
            использовать, изменять и распространять, в том числе в коммерческих целях, сохраняя тексты LICENSE и
            NOTICE. ПО предоставляется «как есть», без гарантий.{' '}
            <a href={about?.licenseUrl ?? 'https://www.apache.org/licenses/LICENSE-2.0'} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-0.5 text-accent-cyan hover:underline">
              apache.org <ExternalLink size={10} />
            </a>
          </p>
          <LegalText title="Полный текст LICENSE" load={api.about.license} />
          <LegalText title="NOTICE (атрибуции сторонних компонентов)" load={api.about.notice} />
        </section>

        <section>
          <h3 className="mb-1 text-2xs font-semibold uppercase tracking-wider text-kraken-muted">Open-source компоненты</h3>
          <div className="grid grid-cols-2 gap-x-6 rounded-md border border-kraken-border bg-kraken-panel px-3 py-2">
            {COMPONENTS.map((component) => (
              <a
                key={component.name}
                href={component.url}
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-2 py-1 text-xs text-kraken-text hover:text-accent-cyan"
              >
                <span className={clsx('h-1.5 w-1.5 shrink-0 rounded-full', component.part === 'ui' ? 'bg-accent-violet' : 'bg-accent-emerald')} />
                <span className="min-w-0 flex-1 truncate">{component.name}</span>
                <Badge color={LICENSE_COLOR[component.license] ?? palette.muted}>{component.license}</Badge>
              </a>
            ))}
          </div>
          <p className="mt-1.5 flex gap-4 text-2xs text-kraken-subtle">
            <span className="flex items-center gap-1"><span className="h-1.5 w-1.5 rounded-full bg-accent-emerald" /> бэкенд (jar)</span>
            <span className="flex items-center gap-1"><span className="h-1.5 w-1.5 rounded-full bg-accent-violet" /> веб-интерфейс</span>
          </p>
        </section>
      </div>
    </Modal>
  );
}
