import React, { useEffect, useRef, useState } from 'react';
import { IonButton, IonChip, IonIcon } from '@ionic/react';
import {
  arrowUpOutline,
  attachOutline,
  checkmarkCircleOutline,
  chevronDownOutline,
  chevronUpOutline,
  closeOutline,
  sparklesOutline,
} from 'ionicons/icons';
import { UploadButton, UploadResult } from '../components/UploadButton';
import {
  formatUploadFailureMessage,
  hasFilesInDataTransfer,
  uploadFilesSequentially,
} from '../components/uploadShared';
import { CATEGORY_ICONS, CHAT_BADGE, COMPOSER_PRESETS, SampleData } from './chatConfig';
import { ChatSamples as ChatSamplesPanel } from './ChatSamples';
import { ComposerPreset, UiConversationCategory } from './chatFeatureUtils';
import { fileIcon } from './chatUtils';

interface Props {
  token: string;
  apiKey: string | null;
  isStreaming: boolean;
  inputText: string;
  attachments: UploadResult[];
  attachmentResetSignal: number;
  uploadError: string | null;
  streamError: string | null;
  uploading: boolean;
  showSamples: boolean;
  canSend: boolean;
  activePreset: ComposerPreset | null;
  composerCategory: UiConversationCategory;
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  onInputChange: (value: string) => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLTextAreaElement>) => void;
  onToggleSamples: () => void;
  onSampleSelect: (sample: SampleData) => void;
  onPresetSelect: (preset: ComposerPreset, starter: string) => void;
  onAttachmentResult: (result: UploadResult) => void;
  onUploadError: (message: string) => void;
  setUploading: (value: boolean) => void;
  onRemoveAttachment: (index: number) => void;
  onMoveAttachment: (index: number, direction: 'up' | 'down') => void;
  onSubmit: () => void;
}

function getPlaceholder(categoryHint: UiConversationCategory, hasAttachments: boolean): string {
  if (hasAttachments) return 'Add context or press Enter to send...';
  if (categoryHint === 'security') {
    return 'Ask about HLS, replay protection, or authentication failures...';
  }
  if (categoryHint === 'incident') {
    return 'Paste logs, alarms, or an incident...';
  }
  if (categoryHint === 'decode') {
    return 'Paste a frame or protocol question...';
  }
  return 'Ask about DLMS/COSEM or paste an input...';
}

function AttachmentMeta({
  attachment,
  index,
  total,
  onMoveAttachment,
  onRemoveAttachment,
}: {
  attachment: UploadResult;
  index: number;
  total: number;
  onMoveAttachment: (index: number, direction: 'up' | 'down') => void;
  onRemoveAttachment: (index: number) => void;
}) {
  const routeLabel = attachment.suggestedEndpoint === 'siconia'
    ? 'SICONIA'
    : attachment.suggestedEndpoint === 'decode'
      ? 'Decode'
      : 'Auto';
  const inputBadge = CHAT_BADGE[attachment.inputClass] || CHAT_BADGE.query;

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        flexWrap: 'wrap',
        padding: '10px 12px',
        borderRadius: 14,
        border: '1px solid var(--chat-border)',
        background: 'var(--chat-surface)',
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
        <span aria-hidden="true">{fileIcon(attachment.type)}</span>
      </div>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div
          style={{
            fontSize: 12.5,
            fontWeight: 700,
            color: 'var(--chat-text)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {attachment.filename}
        </div>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginTop: 4 }}>
          <span
            style={{
              fontSize: 10.5,
              fontWeight: 700,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              color: 'var(--chat-muted-2)',
            }}
          >
            {routeLabel}
          </span>
          <IonChip style={{ margin: 0, height: 20, fontSize: 10.5, '--background': 'var(--chat-primary-soft)', '--color': 'var(--ion-color-primary)' } as React.CSSProperties}>
            <IonIcon icon={inputBadge.icon} style={{ fontSize: 10 }} />
            {inputBadge.label}
          </IonChip>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 4, alignItems: 'center', flexShrink: 0 }}>
        <button
          type="button"
          onClick={() => onMoveAttachment(index, 'up')}
          aria-label={`Move attachment ${attachment.filename} up`}
          disabled={index === 0}
          style={queueActionButtonStyle(index === 0)}
        >
          <IonIcon icon={chevronUpOutline} />
        </button>
        <button
          type="button"
          onClick={() => onMoveAttachment(index, 'down')}
          aria-label={`Move attachment ${attachment.filename} down`}
          disabled={index === total - 1}
          style={queueActionButtonStyle(index === total - 1)}
        >
          <IonIcon icon={chevronDownOutline} />
        </button>
        <button
          type="button"
          onClick={() => onRemoveAttachment(index)}
          aria-label={`Remove attachment ${attachment.filename}`}
          style={queueActionButtonStyle(false)}
        >
          <IonIcon icon={closeOutline} />
        </button>
      </div>
    </div>
  );
}

function queueActionButtonStyle(disabled: boolean): React.CSSProperties {
  return {
    width: 26,
    height: 26,
    borderRadius: 9,
    border: '1px solid var(--chat-border)',
    background: 'transparent',
    color: disabled ? 'var(--chat-muted-2)' : 'var(--chat-muted)',
    cursor: disabled ? 'not-allowed' : 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
    opacity: disabled ? 0.55 : 1,
  };
}

function StatusNotice({ icon, message, tone }: { icon: string; message: string; tone: 'error' | 'info' | 'success' }) {
  const palette = tone === 'error'
    ? { bg: 'rgba(220,38,38,0.08)', border: 'rgba(220,38,38,0.18)', color: 'var(--ion-color-danger)' }
    : tone === 'success'
      ? { bg: 'rgba(22,163,74,0.08)', border: 'rgba(22,163,74,0.18)', color: 'var(--ion-color-success)' }
      : { bg: 'var(--chat-primary-soft)', border: 'rgba(93,125,148,0.16)', color: 'var(--ion-color-primary)' };

  return (
    <div
      role="status"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        color: palette.color,
        background: palette.bg,
        border: `1px solid ${palette.border}`,
        borderRadius: 14,
        padding: '9px 11px',
        fontSize: 12,
        lineHeight: 1.5,
        margin: '0 auto 8px',
        maxWidth: 'var(--input-max-width)',
      }}
    >
      <IonIcon icon={icon} style={{ fontSize: 15, flexShrink: 0 }} />
      <span>{message}</span>
    </div>
  );
}

export const ChatComposer: React.FC<Props> = ({
  token,
  apiKey,
  isStreaming,
  inputText,
  attachments,
  attachmentResetSignal,
  uploadError,
  streamError,
  uploading,
  showSamples,
  canSend,
  activePreset,
  composerCategory,
  textareaRef,
  onInputChange,
  onKeyDown,
  onToggleSamples,
  onSampleSelect,
  onPresetSelect,
  onAttachmentResult,
  onUploadError,
  setUploading,
  onRemoveAttachment,
  onMoveAttachment,
  onSubmit,
}) => {
  useEffect(() => {
    if (!textareaRef.current) return;
    textareaRef.current.style.height = 'auto';
    textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 220)}px`;
  }, [inputText, textareaRef]);

  const [isDragActive, setIsDragActive] = useState(false);
  const dragDepthRef = useRef(0);
  const placeholder = getPlaceholder(composerCategory, attachments.length > 0);
  const dropDisabled = isStreaming || uploading;

  useEffect(() => {
    if (!dropDisabled) return;
    dragDepthRef.current = 0;
    setIsDragActive(false);
  }, [dropDisabled]);

  const canHandleDragEvent = (dataTransfer: DataTransfer | null) => !dropDisabled && hasFilesInDataTransfer(dataTransfer);

  const handleDragEnter = (event: React.DragEvent<HTMLDivElement>) => {
    if (!canHandleDragEvent(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current += 1;
    setIsDragActive(true);
  };

  const handleDragOver = (event: React.DragEvent<HTMLDivElement>) => {
    if (!canHandleDragEvent(event.dataTransfer)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    if (!isDragActive) {
      setIsDragActive(true);
    }
  };

  const handleDragLeave = (event: React.DragEvent<HTMLDivElement>) => {
    if (!canHandleDragEvent(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) {
      setIsDragActive(false);
    }
  };

  const handleDrop = async (event: React.DragEvent<HTMLDivElement>) => {
    if (!canHandleDragEvent(event.dataTransfer)) return;
    event.preventDefault();
    const files = Array.from(event.dataTransfer.files ?? []);
    dragDepthRef.current = 0;
    setIsDragActive(false);

    if (files.length === 0) return;

    setUploading(true);
    const { successes, failures } = await uploadFilesSequentially(files, token, apiKey || '');
    setUploading(false);

    successes.forEach(onAttachmentResult);
    if (failures.length > 0) {
      onUploadError(formatUploadFailureMessage(failures));
    }
  };

  return (
    <div
      style={{
        position: 'fixed',
        bottom: 0,
        left: 0,
        right: 0,
        background: 'linear-gradient(180deg, rgba(255,255,255,0) 0%, var(--chat-shell-overlay) 24%, var(--chat-bg) 100%)',
        padding: '18px var(--input-padding-x) var(--input-bottom-pad)',
        zIndex: 100,
        pointerEvents: 'none',
      }}
    >
      <div style={{ pointerEvents: 'auto' }}>
        {uploadError && (
          <StatusNotice icon={closeOutline} message={uploadError} tone="error" />
        )}
        {streamError && (
          <StatusNotice icon={closeOutline} message={streamError} tone="error" />
        )}
        {uploading && (
          <StatusNotice icon={attachOutline} message="Preparing attachment..." tone="info" />
        )}
        {attachments.length > 0 && !uploading && !uploadError && (
          <StatusNotice
            icon={checkmarkCircleOutline}
            message={`${attachments.length} attachment${attachments.length > 1 ? 's' : ''} ready.`}
            tone="success"
          />
        )}

        <div
          style={{
            maxWidth: 'var(--input-max-width)',
              margin: '0 auto 6px',
              display: 'flex',
            gap: 8,
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
          }}
        >
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {COMPOSER_PRESETS.map((preset) => (
              <button
                key={preset.id}
                type="button"
                onClick={() => onPresetSelect(preset.id, preset.starter)}
                style={{
                  padding: '6px 11px',
                  borderRadius: 999,
                  fontSize: 11,
                  fontWeight: 700,
                  border: '1px solid var(--chat-border)',
                  background: activePreset === preset.id ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
                  color: activePreset === preset.id ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                  cursor: 'pointer',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <IonIcon icon={CATEGORY_ICONS[preset.id]} style={{ fontSize: 12 }} />
                {preset.label}
              </button>
            ))}
          </div>

          <button
            type="button"
            onClick={onToggleSamples}
            aria-pressed={showSamples}
            data-testid="samples-toggle"
            style={{
              padding: '6px 11px',
              borderRadius: 999,
              fontSize: 11,
              fontWeight: 700,
              border: '1px solid var(--chat-border)',
              background: showSamples ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
              color: showSamples ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
              cursor: 'pointer',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              boxShadow: showSamples ? '0 0 0 3px var(--chat-primary-soft)' : 'none',
            }}
          >
            <IonIcon icon={sparklesOutline} style={{ fontSize: 12 }} />
            {showSamples ? 'Hide' : 'Samples'}
          </button>
        </div>

        {showSamples && (
          <ChatSamplesPanel onSelect={onSampleSelect} />
        )}

        <div
          style={{
            maxWidth: 'var(--input-max-width)',
            margin: '0 auto',
            borderRadius: 'var(--input-border-radius)',
            border: isDragActive ? '1px solid var(--ion-color-primary)' : '1px solid var(--chat-input-border)',
            background: isDragActive
              ? 'linear-gradient(0deg, var(--chat-primary-soft), var(--chat-input-bg))'
              : 'var(--chat-input-bg)',
            boxShadow: isDragActive
              ? '0 0 0 3px var(--chat-primary-soft), var(--chat-shadow-lg)'
              : canSend
                ? '0 0 0 3px var(--chat-primary-soft), var(--chat-shadow-lg)'
                : 'var(--chat-shadow-lg)',
            transition: 'box-shadow 0.15s ease, border-color 0.15s ease, background 0.15s ease',
            padding: 11,
          }}
          className="chat-input-wrapper"
          data-testid="composer-drop-target"
          data-drag-active={isDragActive ? 'true' : 'false'}
          onDragEnter={handleDragEnter}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={(event) => {
            void handleDrop(event);
          }}
        >
          {attachments.length > 0 && (
            <div data-testid="attachment-queue" style={{ display: 'grid', gap: 8, marginBottom: 8 }}>
              {attachments.map((attachment, index) => (
                <AttachmentMeta
                  key={`${attachment.filename}-${index}`}
                  attachment={attachment}
                  index={index}
                  total={attachments.length}
                  onMoveAttachment={onMoveAttachment}
                  onRemoveAttachment={onRemoveAttachment}
                />
              ))}
            </div>
          )}

          <div
            style={{
              display: 'flex',
              gap: 'var(--input-gap)',
              alignItems: 'flex-end',
            }}
          >
            <UploadButton
              token={token}
              apiKey={apiKey || ''}
              disabled={isStreaming}
              uploading={uploading}
              resetSignal={attachmentResetSignal}
              setUploading={setUploading}
              onResult={onAttachmentResult}
              onError={onUploadError}
            />

            <div style={{ flex: 1, minWidth: 0 }}>
              <textarea
                ref={textareaRef}
                value={inputText}
                onChange={(event) => onInputChange(event.currentTarget.value)}
                onKeyDown={onKeyDown}
                aria-label="Chat input"
                data-testid="chat-input"
                placeholder={placeholder}
                rows={1}
                disabled={isStreaming}
                style={{
                  width: '100%',
                  border: 'none',
                  outline: 'none',
                  resize: 'none',
                  background: 'transparent',
                  fontSize: 14.5,
                  lineHeight: 1.65,
                  fontFamily: 'inherit',
                  maxHeight: 220,
                  padding: '6px 2px',
                  color: isStreaming ? 'var(--chat-muted)' : 'var(--chat-text)',
                }}
              />
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 10,
                  marginTop: 6,
                  flexWrap: 'wrap',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 11.5, color: 'var(--chat-muted)' }}>
                    Enter to send
                  </span>
                  <span style={{ fontSize: 11, color: 'var(--chat-muted-2)' }}>
                    Shift+Enter for new line
                  </span>
                  {activePreset && (
                    <span
                      style={{
                        fontSize: 11,
                        color: 'var(--ion-color-primary)',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 4,
                      }}
                    >
                      <IonIcon icon={CATEGORY_ICONS[activePreset]} style={{ fontSize: 11 }} />
                      {activePreset}
                    </span>
                  )}
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                  {attachments.length > 0 && (
                    <span style={{ fontSize: 11, color: 'var(--chat-muted-2)' }}>
                      {attachments.length} attachment{attachments.length > 1 ? 's' : ''}
                    </span>
                  )}
                  {inputText.length > 0 && (
                    <span
                      style={{
                        fontSize: 11,
                        color: inputText.length > 2000 ? 'var(--ion-color-danger)' : 'var(--chat-muted-2)',
                        fontVariantNumeric: 'tabular-nums',
                      }}
                    >
                      {inputText.length} chars
                    </span>
                  )}
                </div>
              </div>
            </div>

            <IonButton
              size="small"
              onClick={onSubmit}
              disabled={!canSend}
              title={canSend ? 'Send message' : 'Enter text or add an attachment'}
              aria-label={canSend ? 'Send message' : 'Send disabled until input is provided'}
              data-testid="send-button"
              style={{
                '--border-radius': '15px',
                width: 42,
                height: 42,
                '--padding-start': 0,
                '--padding-end': 0,
                '--background': canSend ? 'var(--ion-color-primary)' : 'var(--chat-surface-2)',
                '--color': canSend ? '#fff' : 'var(--chat-muted-2)',
                flexShrink: 0,
                marginBottom: 2,
              } as React.CSSProperties}
            >
              {isStreaming ? (
                <span style={{ width: 14, height: 14, border: '2px solid rgba(255,255,255,0.25)', borderTopColor: '#fff', borderRadius: '50%', display: 'inline-block', animation: 'spin 0.7s linear infinite' }} />
              ) : (
                <IonIcon icon={arrowUpOutline} />
              )}
            </IonButton>
          </div>
        </div>
      </div>
    </div>
  );
};
