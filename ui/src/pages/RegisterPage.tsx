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

interface RegisterPageProps {
  onRegister: (email: string, username: string, password: string) => Promise<boolean>;
  onSwitchToLogin: () => void;
  isLoading: boolean;
  error: string | null;
}

export const RegisterPage: React.FC<RegisterPageProps> = ({
  onRegister,
  onSwitchToLogin,
  isLoading,
  error,
}) => {
  const [email, setEmail] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [present, dismiss] = useIonLoading();

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setLocalError(null);

    if (!email.trim()) {
      setLocalError('Email is required');
      return;
    }
    if (username.length < 3) {
      setLocalError('Username must be at least 3 characters');
      return;
    }
    if (password.length < 8) {
      setLocalError('Password must be at least 8 characters');
      return;
    }
    if (password !== confirmPassword) {
      setLocalError('Passwords do not match');
      return;
    }

    await present({ message: 'Creating account...', spinner: 'crescent' });
    try {
      const ok = await onRegister(email, username, password);
      if (ok) {
        await dismiss();
        return;
      }
    } finally {
      await dismiss();
    }
  };

  const displayError = localError || error;
  const passwordStrength = getPasswordStrength(password);

  return (
    <IonPage>
      <IonContent style={authPageStyle}>
        <div style={authContentStyle}>
          <div className="auth-frame" style={authFrameStyle}>
            <div className="auth-brand-panel" style={authBrandPanelStyle}>
              <div>
                <div style={authMonogramStyle}>DL</div>
                <div style={authEyebrowStyle}>DLMS / COSEM Assistant</div>
                <h1 style={authHeadlineStyle}>Create an account and keep your investigations in one place.</h1>
                <p style={authBodyStyle}>
                  Save thread history, revisit decodes, and organize protocol, incident, and security work in a simpler workspace.
                </p>
              </div>

              <div style={authFeatureListStyle}>
                <div style={authFeatureStyle}>
                  <span style={authFeatureDotStyle} />
                  Save decode and incident threads
                </div>
                <div style={authFeatureStyle}>
                  <span style={authFeatureDotStyle} />
                  Restore recent conversations after refresh
                </div>
                <div style={authFeatureStyle}>
                  <span style={authFeatureDotStyle} />
                  Keep security and protocol notes together
                </div>
              </div>
            </div>

            <div style={authFormPanelStyle}>
              <h2 style={authSectionTitleStyle}>Create account</h2>
              <p style={authSectionBodyStyle}>Start with the basics. You will be signed in automatically.</p>

              <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column' }}>
                <label style={labelStyle}>Email</label>
                <input
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="you@company.com"
                  autoComplete="email"
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

                <label style={{ ...labelStyle, marginTop: 14 }}>Username</label>
                <input
                  type="text"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="jdoe"
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
                <div style={{ position: 'relative' }}>
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="........"
                    autoComplete="new-password"
                    style={{ ...inputStyle, paddingRight: 42 }}
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

                {password.length > 0 && (
                  <div style={{ marginTop: 6, marginBottom: 2 }}>
                    <div style={{ display: 'flex', gap: 4 }}>
                      {[0, 1, 2, 3].map((index) => (
                        <div
                          key={index}
                          style={{
                            flex: 1,
                            height: 4,
                            borderRadius: 999,
                            background: index < passwordStrength.score
                              ? passwordStrength.color
                              : 'var(--chat-border)',
                            transition: 'background 0.2s ease',
                          }}
                        />
                      ))}
                    </div>
                    <span
                      style={{
                        fontSize: 11,
                        color: passwordStrength.color,
                        marginTop: 4,
                        display: 'block',
                      }}
                    >
                      {passwordStrength.label}
                    </span>
                  </div>
                )}

                <label style={{ ...labelStyle, marginTop: 14 }}>Confirm password</label>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  placeholder="........"
                  autoComplete="new-password"
                  style={{
                    ...inputStyle,
                    borderColor:
                      confirmPassword && confirmPassword !== password
                        ? 'var(--ion-color-danger)'
                        : 'var(--chat-input-border)',
                  }}
                  onFocus={(event) => {
                    event.currentTarget.style.borderColor = 'var(--ion-color-primary)';
                    event.currentTarget.style.boxShadow = '0 0 0 3px var(--chat-primary-soft)';
                  }}
                  onBlur={(event) => {
                    event.currentTarget.style.borderColor =
                      confirmPassword && confirmPassword !== password
                        ? 'var(--ion-color-danger)'
                        : 'var(--chat-input-border)';
                    event.currentTarget.style.boxShadow = 'none';
                  }}
                />

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
                      marginTop: 10,
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
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 8,
                  }}
                >
                  {isLoading ? <><Spinner /> Creating account...</> : 'Create Account'}
                </button>
              </form>

              <p style={footerTextStyle}>
                Already have an account?{' '}
                <span onClick={onSwitchToLogin} style={footerLinkStyle}>
                  Sign in
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

function getPasswordStrength(password: string) {
  let score = 0;
  if (password.length >= 8) score++;
  if (/[A-Z]/.test(password)) score++;
  if (/[0-9]/.test(password)) score++;
  if (/[^A-Za-z0-9]/.test(password)) score++;

  const labels = ['Weak', 'Fair', 'Good', 'Strong'];
  const colors = ['#c05656', '#b7791f', '#4d7a96', '#2f855a'];
  return {
    score,
    label: labels[score - 1] || 'Weak',
    color: colors[score - 1] || '#c05656',
  };
}

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
