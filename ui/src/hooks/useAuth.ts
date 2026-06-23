/**
 * useAuth — JWT authentication hook with session persistence.
 * 
 * Both access token and refresh token are stored in localStorage so that
 * page refresh does NOT require a new login. The access token (1h expiry)
 * is immediately available on mount; if expired, API calls will get a 401
 * and trigger a silent refresh via the refresh token (30d expiry).
 */
import { useState, useCallback, useEffect, useRef } from 'react';

const ACCESS_KEY = 'dlms_access_token';
const REFRESH_KEY = 'dlms_refresh_token';
const USER_KEY = 'dlms_user';

interface User {
  id: string;
  username: string;
  email: string;
  role: string;
}

interface AuthPayload {
  access_token: string;
  refresh_token: string;
  user_id: string;
  username: string;
  role: string;
  email?: string;
}

interface UseAuthReturn {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  isInitialized: boolean;
  error: string | null;
  login: (email: string, password: string) => Promise<boolean>;
  register: (email: string, username: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  refreshToken: () => Promise<boolean>;
}

async function readErrorDetail(response: Response, fallback: string): Promise<string> {
  const raw = await response.text().catch(() => '');
  if (!raw) return fallback;

  try {
    const parsed = JSON.parse(raw) as { detail?: string; error?: string; message?: string };
    return parsed.detail || parsed.error || parsed.message || fallback;
  } catch {
    return raw.trim() || fallback;
  }
}

function getStoredUser(): User | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function getStoredAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY);
}

// Simple JWT decode to check expiry without a library
function isTokenExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    // exp is in seconds, Date.now() is in milliseconds
    return payload.exp * 1000 < Date.now();
  } catch {
    return true; // If we can't decode, treat as expired
  }
}

// Determine if we need to do a silent refresh on mount
function needsRefreshOnMount(): boolean {
  const storedToken = getStoredAccessToken();
  // If we have a valid (non-expired) access token, no refresh needed
  if (storedToken && !isTokenExpired(storedToken)) {
    return false;
  }
  // If access token is missing or expired, check if we have a refresh token
  const storedRefresh = localStorage.getItem(REFRESH_KEY);
  return !!storedRefresh;
}

export function useAuth(): UseAuthReturn {
  const [authState, setAuthState] = useState({
    token: getStoredAccessToken(),
    user: getStoredUser(),
    isInitialized: !needsRefreshOnMount(),
  });
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refreshAttempted = useRef(false);

  const API = (import.meta as unknown as { env: Record<string, string> }).env?.VITE_API_URL || '/api';

  const clearSession = useCallback(() => {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
    setError(null);
    setAuthState(prev => ({ ...prev, token: null, user: null }));
  }, []);

  const saveSession = useCallback((accessToken: string, refreshToken: string, userData: User) => {
    localStorage.setItem(ACCESS_KEY, accessToken);
    localStorage.setItem(REFRESH_KEY, refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(userData));
    setError(null);
    setAuthState({
      token: accessToken,
      user: userData,
      isInitialized: true,
    });
  }, []);

  const refreshTokenFn = useCallback(async (): Promise<boolean> => {
    const storedRefresh = localStorage.getItem(REFRESH_KEY);
    if (!storedRefresh) {
      setError(null);
      setAuthState(prev => ({ ...prev, isInitialized: true }));
      return false;
    }
    try {
      const res = await fetch(`${API}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: storedRefresh }),
      });
      if (!res.ok) {
        clearSession();
        setAuthState(prev => ({ ...prev, isInitialized: true }));
        return false;
      }
      const data = await res.json();
      saveSession(data.access_token, data.refresh_token, {
        id: data.user_id,
        username: data.username,
        email: data.email || '',
        role: data.role,
      });
      return true;
    } catch {
      setError(null);
      clearSession();
      setAuthState(prev => ({ ...prev, isInitialized: true }));
      return false;
    }
  }, [API, clearSession, saveSession]);

  useEffect(() => {
    // Only attempt refresh if the access token is actually expired/missing
    // AND a refresh token exists. If the access token is still valid, skip refresh entirely.
    if (needsRefreshOnMount() && !refreshAttempted.current) {
      refreshAttempted.current = true;
      refreshTokenFn();
    }
  }, [refreshTokenFn]);

  const login = useCallback(
    async (email: string, password: string): Promise<boolean> => {
      setIsLoading(true);
      setError(null);
      try {
        const res = await fetch(`${API}/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email, password }),
        });
        if (!res.ok) {
          setError(await readErrorDetail(res, 'Login failed'));
          setIsLoading(false);
          return false;
        }
        const data = await res.json() as AuthPayload;
        saveSession(data.access_token, data.refresh_token, {
          id: data.user_id,
          username: data.username,
          email,
          role: data.role,
        });
        setIsLoading(false);
        return true;
      } catch {
        setError('Network error. Is the backend running?');
        setIsLoading(false);
        return false;
      }
    },
    [API, saveSession]
  );

  const register = useCallback(
    async (email: string, username: string, password: string): Promise<boolean> => {
      setIsLoading(true);
      setError(null);
      try {
        const res = await fetch(`${API}/auth/register`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(authState.token ? { Authorization: `Bearer ${authState.token}` } : {}),
          },
          body: JSON.stringify({ email, username, password }),
        });
        if (!res.ok) {
          setError(await readErrorDetail(res, 'Registration failed'));
          setIsLoading(false);
          return false;
        }
        const data = await res.json() as AuthPayload;
        saveSession(data.access_token, data.refresh_token, {
          id: data.user_id,
          username: data.username,
          email,
          role: data.role,
        });
        setIsLoading(false);
        return true;
      } catch {
        setError('Network error. Is the backend running?');
        setIsLoading(false);
        return false;
      }
    },
    [API, authState.token, saveSession]
  );

  const logout = useCallback(async (): Promise<void> => {
    if (authState.token) {
      try {
        await fetch(`${API}/auth/logout`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${authState.token}` },
        });
      } catch {
        // Best-effort
      }
    }
    clearSession();
  }, [API, authState.token, clearSession]);

  return {
    user: authState.user,
    token: authState.token,
    isAuthenticated: !!authState.token,
    isLoading,
    isInitialized: authState.isInitialized,
    error,
    login,
    register,
    logout,
    refreshToken: refreshTokenFn,
  };
}
