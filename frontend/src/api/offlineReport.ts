import type { SessionReportBundle } from './types';

const MAX_REPORT_BYTES = 512 * 1024 * 1024;
export const SUPPORTED_FORMAT_VERSION = 1;

/**
 * Reads an exported report ({@code .json.gz} or {@code .json}) entirely in the browser, so a report taken on a
 * production server can be reviewed on any machine without a JVM or a running inspector backend.
 */
export async function readReportFile(file: File): Promise<SessionReportBundle> {
  if (file.size > MAX_REPORT_BYTES) {
    throw new Error(`Файл слишком большой: ${(file.size / 1024 / 1024).toFixed(0)} МБ (максимум 512 МБ)`);
  }
  const head = new Uint8Array(await file.slice(0, 2).arrayBuffer());
  const gzipped = head[0] === 0x1f && head[1] === 0x8b;
  let text: string;
  if (gzipped) {
    if (typeof DecompressionStream === 'undefined') {
      throw new Error('Браузер не поддерживает распаковку gzip: распакуйте .json.gz вручную и откройте .json');
    }
    const stream = file.stream().pipeThrough(new DecompressionStream('gzip'));
    text = await new Response(stream).text();
  } else {
    text = await file.text();
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error('Это не JSON-отчёт JVM Memory & Leak Inspector');
  }
  return validate(parsed);
}

function validate(value: unknown): SessionReportBundle {
  if (typeof value !== 'object' || value === null) {
    throw new Error('Пустой или повреждённый отчёт');
  }
  const bundle = value as Partial<SessionReportBundle>;
  if (bundle.formatVersion !== SUPPORTED_FORMAT_VERSION) {
    throw new Error(`Неподдерживаемая версия формата отчёта: ${String(bundle.formatVersion)}`);
  }
  if (!Array.isArray(bundle.snapshots) || !Array.isArray(bundle.findings)) {
    throw new Error('В отчёте нет снапшотов или диагнозов: файл повреждён');
  }
  return {
    ...bundle,
    metricsHistory: bundle.metricsHistory ?? [],
    diffs: bundle.diffs ?? [],
  } as SessionReportBundle;
}
