import { useState, useEffect, useRef } from 'react';
import { McpStatus } from '../types';

export function useMcpStatus(apiKey: string | null, jwtToken: string | null = null, pollIntervalMs = 30000) {
  const API =
    (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_URL || '/api';
  const [status, setStatus] = useState<McpStatus>({
    reachable: false,
    lastChecked: null,
    checking: false,
  });
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const check = async () => {
    setStatus(s => ({ ...s, checking: true }));
    try {
      // Build auth headers — prefer JWT, fall back to API key
      const headers: HeadersInit = {};
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken}`;
      } else if (apiKey) {
        headers['X-API-Key'] = apiKey;
      } else {
        // No auth available — mark as unavailable
        setStatus({ reachable: false, lastChecked: new Date(), checking: false });
        return;
      }

      const res = await fetch(`${API}/mcp/health`, { headers });
      if (res.ok) {
        const data = await res.json();
        setStatus({ reachable: data.reachable, lastChecked: new Date(), checking: false });
      } else {
        setStatus({ reachable: false, lastChecked: new Date(), checking: false });
      }
    } catch {
      setStatus({ reachable: false, lastChecked: new Date(), checking: false });
    }
  };

  useEffect(() => {
    // Check if we have at least one auth method
    if (!apiKey && !jwtToken) return;
    check();
    timerRef.current = setInterval(check, pollIntervalMs);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [API, apiKey, jwtToken, pollIntervalMs]); // eslint-disable-line react-hooks/exhaustive-deps

  return { status, refresh: check };
}
