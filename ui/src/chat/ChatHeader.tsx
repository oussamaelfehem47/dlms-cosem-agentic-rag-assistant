import React from 'react';
import {
  IonButton,
  IonButtons,
  IonHeader,
  IonIcon,
  IonToolbar,
} from '@ionic/react';
import {
  codeSlashOutline,
  logOutOutline,
  menuOutline,
  moonOutline,
  personCircleOutline,
  sunnyOutline,
  trashOutline,
} from 'ionicons/icons';
import { McpStatus, ThemeMode } from '../types';
import { McpStatusBadge } from '../components/McpStatusBadge';

interface Props {
  themeMode: ThemeMode;
  mcpStatus: McpStatus;
  activeConversationTitle?: string | null;
  onRefreshMcp: () => void;
  onOpenSidebar: () => void;
  onToggleToolPanel: () => void;
  onToggleTheme: () => void;
  onOpenAccountSettings: () => void;
  onClear: () => void;
  onLogout: () => void;
  disableClear: boolean;
}

const iconButtonStyle = {
  '--border-radius': '12px',
  '--padding-start': '7px',
  '--padding-end': '7px',
  width: 34,
  height: 34,
} as React.CSSProperties;

export const ChatHeader: React.FC<Props> = ({
  themeMode,
  mcpStatus,
  activeConversationTitle,
  onRefreshMcp,
  onOpenSidebar,
  onToggleToolPanel,
  onToggleTheme,
  onOpenAccountSettings,
  onClear,
  onLogout,
  disableClear,
}) => {
  return (
    <IonHeader>
      <IonToolbar style={{ '--min-height': '62px', '--padding-start': '8px', '--padding-end': '8px' } as React.CSSProperties}>
        <IonButtons slot="start">
          <IonButton onClick={onOpenSidebar} aria-label="Open conversation sidebar" style={iconButtonStyle}>
            <IonIcon icon={menuOutline} />
          </IonButton>
        </IonButtons>

        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          minWidth: 0,
          flex: 1,
          paddingLeft: 2,
        }}>
          <div style={{ minWidth: 0 }}>
            <div style={{
              fontSize: 14.5,
              fontWeight: 800,
              letterSpacing: '-0.03em',
              color: 'var(--chat-text)',
              lineHeight: 1.05,
            }}>
              DLMS Chat
            </div>
            {activeConversationTitle && (
              <div
                title={activeConversationTitle}
                style={{
                  marginTop: 3,
                  fontSize: 12,
                  lineHeight: 1.35,
                  color: 'var(--chat-muted)',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  maxWidth: 'min(48vw, 420px)',
                }}
              >
                {activeConversationTitle}
              </div>
            )}
          </div>
          <div style={{ flexShrink: 0 }}>
            <McpStatusBadge status={mcpStatus} onRefresh={onRefreshMcp} />
          </div>
        </div>

        <IonButtons slot="end" style={{ gap: 1 }}>
          <IonButton
            onClick={onToggleToolPanel}
            title="Open tools panel (Ctrl+/)"
            aria-label="Open tools panel"
            style={iconButtonStyle}
          >
            <IonIcon icon={codeSlashOutline} />
          </IonButton>
          <IonButton onClick={onToggleTheme} aria-label="Toggle theme" style={iconButtonStyle}>
            <IonIcon icon={themeMode === 'light' ? moonOutline : sunnyOutline} />
          </IonButton>
          <IonButton onClick={onOpenAccountSettings} aria-label="Open account and settings" style={iconButtonStyle}>
            <IonIcon icon={personCircleOutline} />
          </IonButton>
          <IonButton onClick={onClear} disabled={disableClear} aria-label="Clear conversation" style={iconButtonStyle}>
            <IonIcon icon={trashOutline} />
          </IonButton>
          <IonButton onClick={onLogout} aria-label="Sign out" style={iconButtonStyle}>
            <IonIcon icon={logOutOutline} />
          </IonButton>
        </IonButtons>
      </IonToolbar>
    </IonHeader>
  );
};
