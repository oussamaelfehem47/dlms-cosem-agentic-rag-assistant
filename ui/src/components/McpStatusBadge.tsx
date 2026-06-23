import React from 'react';
import { IonIcon } from '@ionic/react';
import { ellipse, refreshOutline } from 'ionicons/icons';
import { McpStatus } from '../types';

interface Props {
  status: McpStatus;
  onRefresh: () => void;
}

export const McpStatusBadge: React.FC<Props> = ({ status, onRefresh }) => {
  const label = status.checking ? 'Checking MCP' : status.reachable ? 'MCP Online' : 'Local Fallback';

  const tooltip = status.checking
    ? 'Checking MCP server availability'
    : status.reachable
    ? 'MCP server online'
    : 'MCP offline, local deterministic decoding remains available';

  const accent = status.checking
    ? 'var(--ion-color-warning)'
    : status.reachable
    ? 'var(--ion-color-success)'
    : 'var(--chat-muted)';

  return (
    <button
      type="button"
      onClick={onRefresh}
      title={tooltip}
      aria-label={`${label}. Refresh status`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 7,
        height: 28,
        padding: '0 9px',
        borderRadius: 999,
        border: '1px solid var(--chat-border)',
        background: 'var(--chat-surface)',
        color: 'var(--chat-text)',
        cursor: 'pointer',
        fontSize: 11,
        fontWeight: 700,
        whiteSpace: 'nowrap',
      }}
    >
      <span style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 17,
        height: 17,
        borderRadius: '50%',
        background: status.checking ? 'rgba(217,119,6,0.14)' : status.reachable ? 'rgba(22,163,74,0.14)' : 'var(--chat-surface-2)',
        color: accent,
        flexShrink: 0,
      }}>
        <IonIcon icon={status.checking ? refreshOutline : ellipse} style={{ fontSize: 10 }} />
      </span>
      <span>{label}</span>
    </button>
  );
};
