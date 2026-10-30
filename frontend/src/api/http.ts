import type { ApiErrorDto } from './types';

/** A failed REST call, carrying the backend's {@code ApiErrorDto} when there is one. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export interface Download {
  blob: Blob;
  fileName: string;
}

async function failure(response: Response): Promise<ApiError> {
  let body: Partial<ApiErrorDto> | null = null;
  try {
    body = (await response.json()) as Partial<ApiErrorDto>;
  } catch {
    // not JSON (proxy error page, network)
  }
  return new ApiError(
    response.status,
    body?.code ?? `HTTP_${response.status}`,
    body?.message ?? (response.statusText || `HTTP ${response.status}`),
  );
}

async function send(method: string, url: string, body?: unknown): Promise<Response> {
  let response: Response;
  try {
    // FormData sets its own multipart Content-Type with the boundary.
    const form = body instanceof FormData;
    response = await fetch(url, {
      method,
      headers: body === undefined || form ? { Accept: 'application/json, text/plain;q=0.9' } : {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
    });
  } catch (error) {
    throw new ApiError(0, 'NETWORK', `Бэкенд инспектора недоступен: ${(error as Error).message}`);
  }
  if (!response.ok) {
    throw await failure(response);
  }
  return response;
}

async function json<T>(method: string, url: string, body?: unknown): Promise<T> {
  const response = await send(method, url, body);
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T;
  }
  return (await response.json()) as T;
}

function fileNameOf(response: Response, fallback: string): string {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^";]+)"?/i.exec(disposition);
  return match?.[1] ?? fallback;
}

export const http = {
  get: <T>(url: string) => json<T>('GET', url),
  /** GET of a plain-text resource (license texts). */
  async text(url: string): Promise<string> {
    return (await send('GET', url)).text();
  },
  post: <T>(url: string, body?: unknown) => json<T>('POST', url, body ?? {}),
  put: <T>(url: string, body?: unknown) => json<T>('PUT', url, body ?? {}),
  del: <T>(url: string) => json<T>('DELETE', url),

  /** Multipart POST of one file. */
  upload<T>(url: string, part: string, file: File): Promise<T> {
    const form = new FormData();
    form.append(part, file, file.name);
    return json<T>('POST', url, form);
  },

  /** POST that returns a file (report export). */
  async download(url: string, body: unknown, fallbackName: string): Promise<Download> {
    const response = await send('POST', url, body);
    return { blob: await response.blob(), fileName: fileNameOf(response, fallbackName) };
  },
};

/** Hands a blob to the browser as a file download. */
export function saveBlob({ blob, fileName }: Download): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) {
    return error.message;
  }
  return String(error);
}
