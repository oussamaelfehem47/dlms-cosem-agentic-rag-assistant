import {
  ActuatorInfoResponse,
  AdminFeedback,
  AdminUser,
  HealthResponse,
  McpHealthResponse,
  ReflectionStatsResponse,
  RegisterUserRequest,
} from './types';

const API =
  (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_URL || '/api';

export class AdminApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'AdminApiError';
    this.status = status;
  }
}

async function readErrorMessage(response: Response, fallback: string): Promise<string> {
  const raw = await response.text().catch(() => '');
  if (!raw) return fallback;

  try {
    const parsed = JSON.parse(raw) as {
      detail?: string;
      error?: string;
      message?: string;
    };
    return parsed.detail || parsed.error || parsed.message || fallback;
  } catch {
    return raw.trim() || fallback;
  }
}

function buildHeaders(token?: string, includeJson = true): HeadersInit {
  return {
    ...(includeJson ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function requestJson<T>(
  path: string,
  options?: {
    token?: string;
    method?: string;
    body?: unknown;
    includeJsonHeader?: boolean;
  },
): Promise<T> {
  const response = await fetch(`${API}${path}`, {
    method: options?.method || 'GET',
    headers: buildHeaders(options?.token, options?.includeJsonHeader !== false),
    body: options?.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (!response.ok) {
    throw new AdminApiError(
      await readErrorMessage(response, 'Admin request failed'),
      response.status,
    );
  }

  return response.json() as Promise<T>;
}

async function requestVoid(
  path: string,
  options?: {
    token?: string;
    method?: string;
    body?: unknown;
    includeJsonHeader?: boolean;
  },
): Promise<void> {
  const response = await fetch(`${API}${path}`, {
    method: options?.method || 'GET',
    headers: buildHeaders(options?.token, options?.includeJsonHeader !== false),
    body: options?.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (!response.ok) {
    throw new AdminApiError(
      await readErrorMessage(response, 'Admin request failed'),
      response.status,
    );
  }
}

export function getReflectionStats(token: string): Promise<ReflectionStatsResponse> {
  return requestJson<ReflectionStatsResponse>('/admin/reflection/stats', { token });
}

export function getDislikedFeedback(token: string, limit = 20): Promise<AdminFeedback[]> {
  return requestJson<AdminFeedback[]>(
    `/admin/reflection/feedback/disliked?limit=${limit}`,
    { token },
  );
}

export function getAdminUsers(token: string): Promise<AdminUser[]> {
  return requestJson<AdminUser[]>('/admin/users', { token });
}

export function deactivateAdminUser(token: string, userId: string): Promise<void> {
  return requestVoid(`/admin/users/${userId}`, { token, method: 'DELETE' });
}

export function activateAdminUser(token: string, userId: string): Promise<AdminUser> {
  return requestJson<AdminUser>(`/admin/users/${userId}/activate`, {
    token,
    method: 'POST',
  });
}

export function updateAdminUserRole(
  token: string,
  userId: string,
  role: string,
): Promise<AdminUser> {
  return requestJson<AdminUser>(`/admin/users/${userId}/role`, {
    token,
    method: 'PATCH',
    body: { role },
  });
}

export function hardDeleteAdminUser(token: string, userId: string): Promise<void> {
  return requestVoid(`/admin/users/${userId}/hard`, { token, method: 'DELETE' });
}

export async function registerUser(
  token: string,
  data: RegisterUserRequest,
): Promise<void> {
  await requestJson<Record<string, unknown>>('/auth/register', {
    token,
    method: 'POST',
    body: data,
  });
}

export function getActuatorHealth(token: string): Promise<HealthResponse> {
  return requestJson<HealthResponse>('/actuator/health', { token });
}

export function getMcpHealth(): Promise<McpHealthResponse> {
  return requestJson<McpHealthResponse>('/mcp/health', {
    includeJsonHeader: false,
  });
}

export function getActuatorInfo(token: string): Promise<ActuatorInfoResponse> {
  return requestJson<ActuatorInfoResponse>('/actuator/info', { token });
}
