import React, { useMemo, useState } from 'react';
import { IonIcon } from '@ionic/react';
import { CATEGORY_ICONS, CHAT_BADGE, SAMPLE_DATA, SampleData } from './chatConfig';
import { UiConversationCategory } from './chatFeatureUtils';

interface Props {
  onSelect: (sample: SampleData) => void;
}

const FILTERS: Array<{ id: 'all' | UiConversationCategory; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'security', label: 'Security' },
  { id: 'decode', label: 'Decode' },
  { id: 'incident', label: 'Incident' },
];

export const ChatSamples: React.FC<Props> = ({ onSelect }) => {
  const [filter, setFilter] = useState<'all' | UiConversationCategory>('all');

  const visibleSamples = useMemo(() => {
    if (filter === 'all') return SAMPLE_DATA;
    return SAMPLE_DATA.filter((sample) => sample.category === filter);
  }, [filter]);

  return (
    <div
      style={{
        maxWidth: 'var(--input-max-width)',
        margin: '0 auto 8px',
        padding: 8,
        borderRadius: 14,
        border: '1px solid var(--chat-border)',
        background: 'var(--chat-surface)',
        display: 'grid',
        gap: 8,
        animation: 'slideUp 0.15s ease',
      }}
    >
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {FILTERS.map((option) => (
          <button
            key={option.id}
            type="button"
            onClick={() => setFilter(option.id)}
            style={{
              padding: '7px 11px',
              borderRadius: 999,
              border: '1px solid var(--chat-border)',
              background: filter === option.id ? 'var(--chat-primary-soft)' : 'var(--chat-bg)',
              color: filter === option.id ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
              cursor: 'pointer',
              fontSize: 11.5,
              fontWeight: 700,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
            }}
          >
            {option.id !== 'all' && (
              <IonIcon icon={CATEGORY_ICONS[option.id]} style={{ fontSize: 12 }} />
            )}
            {option.label}
          </button>
        ))}
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: 8,
        }}
      >
        {visibleSamples.map((sample) => (
          <button
            key={sample.label}
            onClick={() => onSelect(sample)}
            style={{
              padding: '11px 12px',
              borderRadius: 14,
              fontSize: 11,
              border: '1px solid var(--chat-border)',
              background: 'var(--chat-bg)',
              color: 'var(--chat-text)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              textAlign: 'left',
              transition: 'transform 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease',
            }}
            onMouseEnter={(event) => {
              event.currentTarget.style.transform = 'translateY(-1px)';
              event.currentTarget.style.borderColor = 'var(--ion-color-primary)';
              event.currentTarget.style.boxShadow = 'var(--chat-shadow)';
            }}
            onMouseLeave={(event) => {
              event.currentTarget.style.transform = 'translateY(0)';
              event.currentTarget.style.borderColor = 'var(--chat-border)';
              event.currentTarget.style.boxShadow = 'none';
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
              <IonIcon icon={CHAT_BADGE[sample.inputClass].icon} style={{ fontSize: 13 }} />
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 12.5, fontWeight: 700 }}>{sample.label}</div>
              <div
                style={{
                  fontSize: 10.5,
                  color: 'var(--chat-muted-2)',
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 4,
                }}
              >
                <IonIcon icon={CATEGORY_ICONS[sample.category]} style={{ fontSize: 11 }} />
                {sample.category}
              </div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
};
