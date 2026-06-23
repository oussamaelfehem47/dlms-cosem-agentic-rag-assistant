import { useCallback, useEffect, useState } from 'react';
import { ThemeMode } from '../types';

const THEME_STORAGE_KEY = 'theme';

function getInitialTheme(): ThemeMode {
  const saved = localStorage.getItem(THEME_STORAGE_KEY) as ThemeMode | null;
  return saved || 'light';
}

export function useChatShellState() {
  const [themeMode, setThemeMode] = useState<ThemeMode>(getInitialTheme);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [toolPanelOpen, setToolPanelOpen] = useState(false);
  const [showSamples, setShowSamples] = useState(false);
  const [showScrollBtn, setShowScrollBtn] = useState(false);

  useEffect(() => {
    document.body.classList.toggle('dark-theme', themeMode === 'dark');
    localStorage.setItem(THEME_STORAGE_KEY, themeMode);
  }, [themeMode]);

  const toggleTheme = useCallback(() => {
    setThemeMode((current) => (current === 'light' ? 'dark' : 'light'));
  }, []);

  const closeTransientPanels = useCallback(() => {
    setSidebarOpen(false);
    setToolPanelOpen(false);
    setShowSamples(false);
  }, []);

  return {
    themeMode,
    toggleTheme,
    sidebarOpen,
    setSidebarOpen,
    toolPanelOpen,
    setToolPanelOpen,
    showSamples,
    setShowSamples,
    showScrollBtn,
    setShowScrollBtn,
    closeTransientPanels,
  };
}
