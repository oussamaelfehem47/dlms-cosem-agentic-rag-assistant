import React from 'react';
import { IonIcon } from '@ionic/react';
import { chatbubblesOutline } from 'ionicons/icons';
import { CATEGORY_ICONS, EMPTY_STATE_ACTIONS } from './chatConfig';

interface Props {
  onSuggestionClick: (suggestion: string) => void;
}

export const ChatEmptyState: React.FC<Props> = ({ onSuggestionClick }) => {
  return (
    <div
      data-testid="chat-empty-state"
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 'calc(100vh - 56px)',
        padding: '24px 16px 176px',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: 680,
          textAlign: 'center',
          display: 'grid',
          gap: 14,
        }}
      >
        <div
          style={{
            width: 52,
            height: 52,
            margin: '0 auto',
            borderRadius: 16,
            background: 'var(--chat-surface)',
            border: '1px solid var(--chat-border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: 'var(--chat-shadow)',
          }}
        >
          <IonIcon icon={chatbubblesOutline} style={{ fontSize: 22, color: 'var(--ion-color-primary)' }} />
        </div>

        <div>
          <h1
            style={{
              margin: '0 0 8px',
              fontSize: 'clamp(24px, 4vw, 32px)',
              lineHeight: 1.05,
              letterSpacing: '-0.04em',
              color: 'var(--chat-text)',
            }}
          >
            Start a conversation
          </h1>
          <p
            style={{
              margin: 0,
              fontSize: 13,
              lineHeight: 1.5,
              color: 'var(--chat-muted)',
            }}
          >
            Ask a question or paste an input.
          </p>
        </div>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: 8,
          }}
        >
          {EMPTY_STATE_ACTIONS.map((action) => (
            <button
              key={action.label}
              type="button"
              onClick={() => onSuggestionClick(action.prompt)}
              style={{
                textAlign: 'left',
                padding: '12px 14px',
                borderRadius: 14,
                border: '1px solid var(--chat-border)',
                background: 'var(--chat-surface)',
                color: 'var(--chat-text)',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
              }}
            >
              <div
                style={{
                  width: 28,
                  height: 28,
                  borderRadius: 10,
                  background: 'var(--chat-primary-soft)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'var(--ion-color-primary)',
                  flexShrink: 0,
                }}
              >
                <IonIcon icon={CATEGORY_ICONS[action.category]} style={{ fontSize: 14 }} />
              </div>
              <div style={{ minWidth: 0, textAlign: 'left' }}>
                <div style={{ fontSize: 12.5, fontWeight: 700 }}>{action.label}</div>
                <div
                  style={{
                    fontSize: 10.5,
                    color: 'var(--chat-muted-2)',
                    textTransform: 'uppercase',
                    letterSpacing: '0.06em',
                  }}
                >
                  {action.category}
                </div>
              </div>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
