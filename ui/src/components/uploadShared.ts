import { InputClass } from '../types';

export interface UploadResult {
  filename: string;
  text: string;
  inputClass: InputClass;
  type: string;
  suggestedEndpoint?: 'decode' | 'siconia';
}

export interface UploadFailure {
  fileName: string;
  error: string;
}

interface UploadApiResponse {
  text?: string;
  analysis?: string;
  input_class?: InputClass;
  type?: string;
  suggested_endpoint?: 'decode' | 'siconia';
  detail?: string;
  error?: string;
}

export const ACCEPTED_UPLOAD_TYPES = '.hex,.txt,.xml,.log,.pdf,.docx';

const API = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_URL || '/api';

async function readUploadError(response: Response, fallback: string): Promise<string> {
  const raw = await response.text().catch(() => '');
  if (!raw) return fallback;

  try {
    const parsed = JSON.parse(raw) as UploadApiResponse;
    return parsed.detail || parsed.error || fallback;
  } catch {
    return raw.trim() || fallback;
  }
}

export async function uploadFile(
  file: File,
  token: string | null,
  apiKey: string,
): Promise<{ ok: true; result: UploadResult } | { ok: false; error: string }> {
  const form = new FormData();
  form.append('file', file);

  const authHeader: Record<string, string> = token
    ? { Authorization: `Bearer ${token}` }
    : { 'X-API-Key': apiKey };

  try {
    const res = await fetch(`${API}/upload`, { method: 'POST', headers: authHeader, body: form });
    if (res.status === 413) return { ok: false, error: 'File too large (max 10 MB)' };
    if (res.status === 401) return { ok: false, error: 'Please sign in again before uploading.' };
    if (res.status === 403) return { ok: false, error: 'You do not have permission to upload files.' };
    if (res.status === 400) return { ok: false, error: await readUploadError(res, 'Unsupported file type') };
    if (!res.ok) return { ok: false, error: await readUploadError(res, `Upload failed (HTTP ${res.status})`) };

    const data: UploadApiResponse = await res.json();
    const text: string = data.text ?? data.analysis ?? '';
    if (!text) return { ok: false, error: 'No content could be extracted from file' };

    return {
      ok: true,
      result: {
        filename: file.name,
        text,
        inputClass: data.input_class ?? 'query',
        type: data.type ?? 'file',
        suggestedEndpoint: data.suggested_endpoint ?? undefined,
      },
    };
  } catch {
    return { ok: false, error: 'Network error during upload' };
  }
}

export async function uploadFilesSequentially(
  files: File[],
  token: string | null,
  apiKey: string,
): Promise<{ successes: UploadResult[]; failures: UploadFailure[] }> {
  const successes: UploadResult[] = [];
  const failures: UploadFailure[] = [];

  for (const file of files) {
    const result = await uploadFile(file, token, apiKey);
    if (result.ok) {
      successes.push(result.result);
      continue;
    }

    failures.push({
      fileName: file.name,
      error: result.error,
    });
  }

  return { successes, failures };
}

export function hasFilesInDataTransfer(dataTransfer: DataTransfer | null | undefined): boolean {
  if (!dataTransfer) return false;

  const types = Array.from(dataTransfer.types ?? []);
  if (types.includes('Files')) return true;

  const items = Array.from(dataTransfer.items ?? []);
  if (items.some((item) => item.kind === 'file')) return true;

  return (dataTransfer.files?.length ?? 0) > 0;
}

export function formatUploadFailureMessage(failures: UploadFailure[]): string {
  if (failures.length === 0) return '';

  const details = failures
    .slice(0, 3)
    .map(({ fileName, error }) => `${fileName}: ${error}`)
    .join(' | ');

  if (failures.length === 1) {
    return details;
  }

  const remainder = failures.length > 3 ? ` | +${failures.length - 3} more` : '';
  return `${failures.length} files could not be uploaded. ${details}${remainder}`;
}
