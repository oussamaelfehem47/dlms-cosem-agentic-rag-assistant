import React, { useState } from 'react';
import { IonContent, IonPage, useIonLoading } from '@ionic/react';
import {
  authBodyStyle,
  authBrandPanelStyle,
  authContentStyle,
  authEyebrowStyle,
  authFeatureDotStyle,
  authFeatureListStyle,
  authFeatureStyle,
  authFormPanelStyle,
  authFrameStyle,
  authHeadlineStyle,
  authMonogramStyle,
  authPageStyle,
  authSectionBodyStyle,
  authSectionTitleStyle,
  footerLinkStyle,
  footerTextStyle,
  inlineStyleSheet,
  inputStyle,
  labelStyle,
} from './authStyles';

interface LoginPageProps {
  onLogin: (identifier: string, password: string) => Promise<boolean>;
  isLoading: boolean;
  error: string | null;
}

export const LoginPage: React.FC<LoginPageProps> = ({
  onLogin,
  isLoading,
  error,
}) => {
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [present, dismiss] = useIonLoading();

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setLocalError(null);

    if (!identifier.trim()) {
      setLocalError('Email or username is required');
      return;
    }
    if (password.length < 8) {
      setLocalError('Password must be at least 8 characters');
      return;
    }

    await present({ message: 'Signing in...', spinner: 'crescent' });
    try {
      const success = await onLogin(identifier, password);
      if (success) await dismiss();
    } finally {
      await dismiss();
    }
  };

  const displayError = localError || error;

  return (
    <IonPage>
      <IonContent style={authPageStyle}>
        <div style={authContentStyle}>
          <div className="auth-frame" style={authFrameStyle}>
            <div className="auth-brand-panel" style={authBrandPanelStyle}>
              <div>
                <div style={authMonogramStyle}>DL</div>
                <div style={authEyebrowStyle}>DLMS / COSEM Assistant</div>
                <h1 style={authHeadlineStyle}>Protocol decoding, incident analysis, and security help.</h1>
                <p style={authBodyStyle}>
                  A quieter workspace for smart metering support. Decode frames, inspect traces, and revisit investigations without the noise.
                </p>
              </div>

              <div style={authFeatureListStyle}>
                <div style={authFeatureStyle}>
                  <span style={authFeatureDotStyle} />
                  Decode DLMS and OBIS inputs
                </div>
                <div style={authFeatureStyle}>
                  <span style={authFeatureDotStyle} />
                  Review alarms, logs, and XML traces
                </div>
                <div style={authFeatureStyle}>
                  <span style={authFeatureDotStyle} />
                  Reopen saved investigations quickly
                </div>
              </div>
            </div>

            <div style={authFormPanelStyle}>
              <h2 style={authSectionTitleStyle}>Welcome back</h2>
              <p style={authSectionBodyStyle}>Sign in to continue.</p>

              <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
                <label style={labelStyle}>Email or username</label>
                <input
                  type="text"
                  value={identifier}
                  onChange={(event) => setIdentifier(event.target.value)}
                  placeholder="you@company.com"
                  autoComplete="username"
                  style={inputStyle}
                  onFocus={(event) => {
                    event.currentTarget.style.borderColor = 'var(--ion-color-primary)';
                    event.currentTarget.style.boxShadow = '0 0 0 3px var(--chat-primary-soft)';
                  }}
                  onBlur={(event) => {
                    event.currentTarget.style.borderColor = 'var(--chat-input-border)';
                    event.currentTarget.style.boxShadow = 'none';
                  }}
                />

                <label style={{ ...labelStyle, marginTop: 14 }}>Password</label>
                <div style={{ position: 'relative', marginBottom: 8 }}>
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="........"
                    autoComplete="current-password"
                    style={{ ...inputStyle, marginBottom: 0, paddingRight: 42 }}
                    onFocus={(event) => {
                      event.currentTarget.style.borderColor = 'var(--ion-color-primary)';
                      event.currentTarget.style.boxShadow = '0 0 0 3px var(--chat-primary-soft)';
                    }}
                    onBlur={(event) => {
                      event.currentTarget.style.borderColor = 'var(--chat-input-border)';
                      event.currentTarget.style.boxShadow = 'none';
                    }}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((value) => !value)}
                    style={{
                      position: 'absolute',
                      right: 12,
                      top: '50%',
                      transform: 'translateY(-50%)',
                      background: 'none',
                      border: 'none',
                      cursor: 'pointer',
                      color: 'var(--chat-muted-2)',
                      fontSize: 13,
                      padding: 0,
                      lineHeight: 1,
                      fontWeight: 700,
                    }}
                    title={showPassword ? 'Hide password' : 'Show password'}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? 'Hide' : 'Show'}
                  </button>
                </div>

                {displayError && (
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'flex-start',
                      gap: 8,
                      background: 'rgba(192,86,86,0.08)',
                      border: '1px solid rgba(192,86,86,0.16)',
                      borderRadius: 12,
                      padding: '10px 12px',
                      marginTop: 4,
                      marginBottom: 4,
                    }}
                  >
                    <span style={{ fontSize: 14, flexShrink: 0, color: 'var(--ion-color-danger)' }}>!</span>
                    <span style={{ fontSize: 13, color: 'var(--ion-color-danger)', lineHeight: 1.5 }}>
                      {displayError}
                    </span>
                  </div>
                )}

                <button
                  type="submit"
                  disabled={isLoading}
                  style={{
                    width: '100%',
                    padding: '12px 0',
                    borderRadius: 12,
                    border: 'none',
                    background: isLoading
                      ? 'var(--chat-surface-2)'
                      : 'linear-gradient(135deg, var(--chat-gradient-start), var(--chat-gradient-end))',
                    color: isLoading ? 'var(--chat-muted-2)' : '#fff',
                    fontSize: 15,
                    fontWeight: 700,
                    cursor: isLoading ? 'not-allowed' : 'pointer',
                    marginTop: 16,
                    boxShadow: isLoading ? 'none' : '0 10px 24px rgba(35,40,44,0.10)',
                    transition: 'all 0.15s ease',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 8,
                  }}
                >
                  {isLoading ? <><Spinner /> Signing in...</> : 'Sign In'}
                </button>
              </form>

              <p style={footerTextStyle}>
                Need access?{' '}
                <span style={footerLinkStyle}>
                  Contact an administrator to create an account.
                </span>
              </p>
            </div>
          </div>
        </div>

        <style>{inlineStyleSheet}</style>
      </IonContent>
    </IonPage>
  );
};

const Spinner = () => (
  <span
    style={{
      width: 14,
      height: 14,
      border: '2px solid rgba(255,255,255,0.35)',
      borderTopColor: '#fff',
      borderRadius: '50%',
      display: 'inline-block',
      animation: 'spin 0.7s linear infinite',
    }}
  />
);
