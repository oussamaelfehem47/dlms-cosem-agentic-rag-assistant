import { useState, useCallback, useEffect } from 'react';
import { getUserApiKeyStorageKey, migrateLegacyApiKey } from '../utils/userScopedStorage';

export function useApiKey(userId: string) {
  const [apiKey, setApiKeyState] = useState<string>(() => {
    migrateLegacyApiKey(userId);
    return userId ? localStorage.getItem(getUserApiKeyStorageKey(userId)) || '' : '';
  });

  useEffect(() => {
    migrateLegacyApiKey(userId);
    setApiKeyState(userId ? localStorage.getItem(getUserApiKeyStorageKey(userId)) || '' : '');
  }, [userId]);

  const setApiKey = useCallback((key: string) => {
    if (!userId) return;
    const trimmedKey = key.trim();
    localStorage.setItem(getUserApiKeyStorageKey(userId), trimmedKey);
    setApiKeyState(trimmedKey);
  }, [userId]);

  const clearApiKey = useCallback(() => {
    if (!userId) return;
    localStorage.removeItem(getUserApiKeyStorageKey(userId));
    setApiKeyState('');
  }, [userId]);

  return { apiKey, setApiKey, clearApiKey, hasKey: apiKey.length > 0 };
}
