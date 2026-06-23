import React, { useEffect, useMemo, useState } from 'react';
import {
  IonBadge,
  IonButton,
  IonButtons,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonSpinner,
  IonToolbar,
} from '@ionic/react';
import {
  arrowBackOutline,
  checkmarkCircleOutline,
  closeCircleOutline,
  keyOutline,
  logOutOutline,
  moonOutline,
  personCircleOutline,
  sunnyOutline,
} from 'ionicons/icons';
import { useApiKey } from '../hooks/useApiKey';
import { CurrentUserProfile, ThemeMode } from '../types';

const THEME_STORAGE_KEY = 'theme';

function getInitialTheme(): ThemeMode {
  const saved = localStorage.getItem(THEME_STORAGE_KEY) as ThemeMode | null;
  return saved || 'light';
}

function formatCreatedAt(value?: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}

function formatRole(value?: string): string {
  if (!value) return '-';
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
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

interface Props {
  token: string;
  userId: string;
  username?: string;
  onBack: () => void;
  onLogout: () => Promise<void> | void;
}

export const AccountSettingsPage: React.FC<Props> = ({
  token,
  userId,
  username,
  onBack,
  onLogout,
}) => {
  const [profile, setProfile] = useState<CurrentUserProfile | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [loadingProfile, setLoadingProfile] = useState(true);
  const [themeMode, setThemeMode] = useState<ThemeMode>(getInitialTheme);
  const [savingApiKey, setSavingApiKey] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const { apiKey, setApiKey, clearApiKey } = useApiKey(userId);
  const [apiKeyDraft, setApiKeyDraft] = useState(apiKey);

  const API =
    (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_URL || '/api';

  useEffect(() => {
    let mounted = true;
    setLoadingProfile(true);
    setProfileError(null);

    void (async () => {
      try {
        const response = await fetch(`${API}/me`, {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });

        if (!response.ok) {
          const detail = await readErrorDetail(response, 'Could not load profile information.');
          if (mounted) {
            setProfileError(detail);
            setLoadingProfile(false);
          }
          return;
        }

        const payload = (await response.json()) as CurrentUserProfile;
        if (!mounted) return;
        setProfile(payload);
        setLoadingProfile(false);
      } catch {
        if (!mounted) return;
        setProfileError('Network error while loading profile.');
        setLoadingProfile(false);
      }
    })();

    return () => {
      mounted = false;
    };
  }, [API, token]);

  useEffect(() => {
    document.body.classList.toggle('dark-theme', themeMode === 'dark');
    localStorage.setItem(THEME_STORAGE_KEY, themeMode);
  }, [themeMode]);

  useEffect(() => {
    setApiKeyDraft(apiKey);
  }, [apiKey]);

  const hasUnsavedApiKeyChanges = useMemo(
    () => apiKeyDraft.trim() !== apiKey,
    [apiKey, apiKeyDraft],
  );

  const handleSaveApiKey = () => {
    setSavingApiKey(true);
    setApiKey(apiKeyDraft);
    setSavingApiKey(false);
  };

  const handleClearApiKey = () => {
    setSavingApiKey(true);
    clearApiKey();
    setApiKeyDraft('');
    setSavingApiKey(false);
  };

  const handleLogout = async () => {
    setSigningOut(true);
    try {
      await onLogout();
    } finally {
      setSigningOut(false);
    }
  };

  const displayUsername = profile?.username || username || '-';
  const displayEmail = profile?.email || '-';
  const displayRole = formatRole(profile?.role);
  const displayCreatedAt = formatCreatedAt(profile?.created_at);
  const displayStatus = profile?.active ?? true;

  return (
    <IonPage style={{ background: 'var(--chat-bg)' }}>
      <IonHeader>
        <IonToolbar style={{ '--min-height': '62px', '--padding-start': '8px', '--padding-end': '8px' } as React.CSSProperties}>
          <IonButtons slot="start">
            <IonButton onClick={onBack} aria-label="Back to chat">
              <IonIcon icon={arrowBackOutline} />
            </IonButton>
          </IonButtons>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 700, color: 'var(--chat-text)' }}>
            <IonIcon icon={personCircleOutline} />
            Account & Settings
          </div>
        </IonToolbar>
      </IonHeader>

      <IonContent style={{ '--background': 'var(--chat-bg)' }}>
        <div style={{ maxWidth: 760, margin: '0 auto', padding: '20px 16px 28px' }}>
          <section
            style={{
              border: '1px solid var(--chat-border)',
              borderRadius: 14,
              background: 'var(--chat-surface)',
              padding: 16,
              boxShadow: 'var(--chat-shadow)',
            }}
          >
            <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--chat-text)', marginBottom: 10 }}>
              Account
            </div>
            {loadingProfile ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'var(--chat-muted)' }}>
                <IonSpinner name="crescent" />
                Loading profile...
              </div>
            ) : (
              <>
                {profileError && (
                  <div style={{ marginBottom: 10, color: 'var(--chat-danger, #b91c1c)', fontSize: 13 }}>
                    {profileError}
                  </div>
                )}
                <div style={{ display: 'grid', gridTemplateColumns: 'minmax(120px, 180px) 1fr', gap: 10, fontSize: 14 }}>
                  <div style={{ color: 'var(--chat-muted)' }}>Username</div>
                  <div style={{ color: 'var(--chat-text)', fontWeight: 600 }}>{displayUsername}</div>
                  <div style={{ color: 'var(--chat-muted)' }}>Email</div>
                  <div style={{ color: 'var(--chat-text)' }}>{displayEmail}</div>
                  <div style={{ color: 'var(--chat-muted)' }}>Role</div>
                  <div style={{ color: 'var(--chat-text)' }}>{displayRole}</div>
                  <div style={{ color: 'var(--chat-muted)' }}>Created</div>
                  <div style={{ color: 'var(--chat-text)' }}>{displayCreatedAt}</div>
                  <div style={{ color: 'var(--chat-muted)' }}>Status</div>
                  <div>
                    <IonBadge
                      color={displayStatus ? 'success' : 'medium'}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
                    >
                      <IonIcon icon={displayStatus ? checkmarkCircleOutline : closeCircleOutline} />
                      {displayStatus ? 'Active' : 'Inactive'}
                    </IonBadge>
                  </div>
                </div>
              </>
            )}
          </section>

          <section
            style={{
              marginTop: 14,
              border: '1px solid var(--chat-border)',
              borderRadius: 14,
              background: 'var(--chat-surface)',
              padding: 16,
              boxShadow: 'var(--chat-shadow)',
            }}
          >
            <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--chat-text)', marginBottom: 12 }}>
              Settings
            </div>

            <div style={{ marginBottom: 14 }}>
              <div style={{ fontSize: 13, color: 'var(--chat-muted)', marginBottom: 6 }}>Theme</div>
              <div style={{ display: 'flex', gap: 8 }}>
                <IonButton
                  size="small"
                  fill={themeMode === 'light' ? 'solid' : 'outline'}
                  onClick={() => setThemeMode('light')}
                >
                  <IonIcon icon={sunnyOutline} slot="start" />
                  Light
                </IonButton>
                <IonButton
                  size="small"
                  fill={themeMode === 'dark' ? 'solid' : 'outline'}
                  onClick={() => setThemeMode('dark')}
                >
                  <IonIcon icon={moonOutline} slot="start" />
                  Dark
                </IonButton>
              </div>
            </div>

            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 13, color: 'var(--chat-muted)', marginBottom: 6 }}>API key</div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <input
                  value={apiKeyDraft}
                  onChange={(event) => setApiKeyDraft(event.target.value)}
                  placeholder="Paste API key"
                  style={{
                    flex: '1 1 320px',
                    minWidth: 220,
                    border: '1px solid var(--chat-border)',
                    background: 'var(--chat-bg-soft, var(--chat-bg))',
                    color: 'var(--chat-text)',
                    borderRadius: 10,
                    padding: '10px 12px',
                    fontSize: 14,
                  }}
                />
                <IonButton
                  size="small"
                  onClick={handleSaveApiKey}
                  disabled={!hasUnsavedApiKeyChanges || savingApiKey}
                >
                  <IonIcon icon={keyOutline} slot="start" />
                  Save
                </IonButton>
                <IonButton
                  size="small"
                  fill="outline"
                  onClick={handleClearApiKey}
                  disabled={!apiKey && !apiKeyDraft}
                >
                  Clear
                </IonButton>
              </div>
            </div>

            <IonButton color="danger" fill="outline" onClick={() => void handleLogout()} disabled={signingOut}>
              <IonIcon icon={logOutOutline} slot="start" />
              Sign out
            </IonButton>
          </section>
        </div>
      </IonContent>
    </IonPage>
  );
};
