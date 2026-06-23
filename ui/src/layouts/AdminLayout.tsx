import React from 'react';
import {
  IonBadge,
  IonContent,
  IonHeader,
  IonIcon,
  IonPage,
  IonToolbar,
} from '@ionic/react';
import {
  arrowBackOutline,
  barChartOutline,
  chatbubbleEllipsesOutline,
  hardwareChipOutline,
  peopleOutline,
} from 'ionicons/icons';
import './AdminLayout.css';

export type AdminSection = 'overview' | 'users' | 'feedback' | 'system';

const NAV_ITEMS: Array<{ id: AdminSection; label: string; icon: string }> = [
  { id: 'overview', label: 'Overview', icon: barChartOutline },
  { id: 'users', label: 'Users', icon: peopleOutline },
  { id: 'feedback', label: 'Feedback', icon: chatbubbleEllipsesOutline },
  { id: 'system', label: 'System', icon: hardwareChipOutline },
];

interface Props {
  activeSection: AdminSection;
  username?: string;
  role?: string;
  onNavigate: (section: AdminSection) => void;
  onBackToChat: () => void;
  children: React.ReactNode;
}

function formatRole(role?: string): string {
  if (!role) return 'ADMIN';
  return role.toUpperCase();
}

export const AdminLayout: React.FC<Props> = ({
  activeSection,
  username,
  role,
  onNavigate,
  onBackToChat,
  children,
}) => {
  const roleLabel = formatRole(role);

  return (
    <IonPage style={{ background: 'var(--chat-bg)' }}>
      <IonHeader>
        <IonToolbar
          style={
            {
              '--min-height': '68px',
              '--padding-start': '12px',
              '--padding-end': '12px',
            } as React.CSSProperties
          }
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 16,
              width: '100%',
              padding: '10px 4px',
            }}
          >
            <div style={{ minWidth: 0 }}>
              <div
                style={{
                  fontSize: 18,
                  fontWeight: 800,
                  color: 'var(--chat-text)',
                  letterSpacing: '-0.03em',
                }}
              >
                DLMS Admin Panel
              </div>
              <div
                style={{
                  marginTop: 4,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  flexWrap: 'wrap',
                  color: 'var(--chat-muted)',
                  fontSize: 12.5,
                }}
              >
                <span>{username || 'Administrator'}</span>
                <IonBadge
                  style={{
                    '--background': 'var(--chat-primary-soft)',
                    '--color': 'var(--ion-color-primary)',
                    border: '1px solid var(--chat-border)',
                    borderRadius: 999,
                    padding: '4px 8px',
                    fontSize: 10.5,
                    fontWeight: 800,
                    letterSpacing: '0.04em',
                  } as React.CSSProperties}
                >
                  {roleLabel}
                </IonBadge>
              </div>
            </div>

            <button
              type="button"
              onClick={onBackToChat}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                padding: '9px 12px',
                borderRadius: 12,
                border: '1px solid var(--chat-border)',
                background: 'var(--chat-surface)',
                color: 'var(--chat-text)',
                cursor: 'pointer',
                fontSize: 12.5,
                fontWeight: 700,
                flexShrink: 0,
              }}
            >
              <IonIcon icon={arrowBackOutline} style={{ fontSize: 14 }} />
              Back to Chat
            </button>
          </div>
        </IonToolbar>
      </IonHeader>

      <IonContent style={{ '--background': 'var(--chat-bg)' }}>
        <div className="admin-shell">
          <aside className="admin-sidebar">
            {NAV_ITEMS.map((item) => {
              const active = item.id === activeSection;

              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => onNavigate(item.id)}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    width: '100%',
                    padding: '12px 14px',
                    borderRadius: 16,
                    border: `1px solid ${active ? 'rgba(93,125,148,0.22)' : 'var(--chat-border)'}`,
                    borderLeft: `4px solid ${active ? 'var(--chat-primary)' : 'transparent'}`,
                    background: active ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
                    color: active ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                    cursor: 'pointer',
                    fontSize: 13,
                    fontWeight: active ? 800 : 700,
                    textAlign: 'left',
                    boxShadow: active ? 'var(--chat-shadow)' : 'none',
                  }}
                >
                  <IonIcon icon={item.icon} style={{ fontSize: 16, flexShrink: 0 }} />
                  {item.label}
                </button>
              );
            })}
          </aside>

          <main className="admin-main">
            <div className="admin-mobile-nav">
              {NAV_ITEMS.map((item) => {
                const active = item.id === activeSection;

                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => onNavigate(item.id)}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 6,
                      padding: '9px 12px',
                      borderRadius: 999,
                      border: `1px solid ${active ? 'rgba(93,125,148,0.22)' : 'var(--chat-border)'}`,
                      background: active ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
                      color: active ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                      cursor: 'pointer',
                      fontSize: 12,
                      fontWeight: 800,
                      whiteSpace: 'nowrap',
                    }}
                  >
                    <IonIcon icon={item.icon} style={{ fontSize: 14 }} />
                    {item.label}
                  </button>
                );
              })}
            </div>
            {children}
          </main>
        </div>
      </IonContent>
    </IonPage>
  );
};
