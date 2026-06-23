import type { CSSProperties } from 'react';

export const authPageStyle: CSSProperties = {
  '--background': 'var(--chat-bg)',
} as CSSProperties;

export const authContentStyle: CSSProperties = {
  minHeight: '100vh',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: '24px 16px',
  background:
    'radial-gradient(circle at top left, rgba(77,122,150,0.08), rgba(77,122,150,0) 24%)',
};

export const authFrameStyle: CSSProperties = {
  width: '100%',
  maxWidth: 960,
  display: 'grid',
  gridTemplateColumns: 'minmax(0, 1fr) minmax(320px, 420px)',
  borderRadius: 24,
  overflow: 'hidden',
  border: '1px solid var(--chat-border)',
  background: 'var(--chat-surface)',
  boxShadow: '0 24px 60px rgba(35,40,44,0.10)',
};

export const authBrandPanelStyle: CSSProperties = {
  padding: '56px 48px',
  display: 'flex',
  flexDirection: 'column',
  justifyContent: 'space-between',
  minWidth: 0,
  background:
    'linear-gradient(180deg, color-mix(in srgb, var(--chat-surface) 72%, var(--chat-primary-soft)) 0%, var(--chat-surface) 100%)',
  borderRight: '1px solid var(--chat-border)',
};

export const authFormPanelStyle: CSSProperties = {
  background: 'var(--chat-surface)',
  padding: '48px 40px',
  display: 'flex',
  flexDirection: 'column',
  justifyContent: 'center',
};

export const authMonogramStyle: CSSProperties = {
  width: 48,
  height: 48,
  borderRadius: 16,
  background: 'linear-gradient(135deg, var(--chat-gradient-start), var(--chat-gradient-end))',
  color: '#fff',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 15,
  fontWeight: 800,
  letterSpacing: '0.08em',
  boxShadow: '0 12px 24px rgba(35,40,44,0.10)',
  marginBottom: 24,
};

export const authEyebrowStyle: CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  letterSpacing: '0.12em',
  textTransform: 'uppercase',
  color: 'var(--chat-muted-2)',
  marginBottom: 14,
};

export const authHeadlineStyle: CSSProperties = {
  fontSize: 'clamp(30px, 4vw, 40px)',
  lineHeight: 1.05,
  letterSpacing: '-0.05em',
  color: 'var(--chat-text)',
  margin: '0 0 14px',
};

export const authBodyStyle: CSSProperties = {
  fontSize: 14,
  lineHeight: 1.7,
  color: 'var(--chat-muted)',
  margin: 0,
  maxWidth: 360,
};

export const authFeatureListStyle: CSSProperties = {
  display: 'grid',
  gap: 10,
  marginTop: 24,
};

export const authFeatureStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  fontSize: 13,
  color: 'var(--chat-muted)',
};

export const authFeatureDotStyle: CSSProperties = {
  width: 8,
  height: 8,
  borderRadius: '50%',
  background: 'var(--ion-color-primary)',
  flexShrink: 0,
};

export const authSectionTitleStyle: CSSProperties = {
  fontSize: 24,
  fontWeight: 700,
  color: 'var(--chat-text)',
  margin: '0 0 8px',
  letterSpacing: '-0.03em',
};

export const authSectionBodyStyle: CSSProperties = {
  fontSize: 13.5,
  color: 'var(--chat-muted)',
  margin: '0 0 28px',
  lineHeight: 1.6,
};

export const labelStyle: CSSProperties = {
  fontSize: 12.5,
  fontWeight: 700,
  color: 'var(--chat-text)',
  marginBottom: 6,
  display: 'block',
};

export const inputStyle: CSSProperties = {
  width: '100%',
  padding: '11px 14px',
  borderRadius: 12,
  border: '1px solid var(--chat-input-border)',
  fontSize: 14,
  marginBottom: 0,
  outline: 'none',
  boxSizing: 'border-box',
  color: 'var(--chat-text)',
  background: 'color-mix(in srgb, var(--chat-input-bg) 88%, white)',
  transition: 'border-color 0.15s ease, box-shadow 0.15s ease',
};

export const footerTextStyle: CSSProperties = {
  textAlign: 'center',
  fontSize: 13,
  color: 'var(--chat-muted)',
  marginTop: 24,
  marginBottom: 0,
};

export const footerLinkStyle: CSSProperties = {
  color: 'var(--ion-color-primary)',
  fontWeight: 700,
  cursor: 'pointer',
};

export const inlineStyleSheet = `
  @media (max-width: 720px) {
    .auth-frame {
      grid-template-columns: 1fr !important;
      max-width: 440px !important;
    }

    .auth-brand-panel {
      display: none !important;
    }
  }
`;
