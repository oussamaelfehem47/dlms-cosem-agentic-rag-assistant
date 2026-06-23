/**
 * UploadButton - paperclip icon that POSTs a file to /upload.
 * Returns structured result; does NOT touch the textarea.
 */
import React, { useEffect, useRef } from 'react';
import { ACCEPTED_UPLOAD_TYPES, uploadFile, type UploadResult } from './uploadShared';

export type { UploadResult } from './uploadShared';

interface Props {
  token: string | null;
  apiKey: string;
  disabled?: boolean;
  onResult: (r: UploadResult) => void;
  onError: (msg: string) => void;
  uploading: boolean;
  resetSignal?: number;
  setUploading: (v: boolean) => void;
}

export const UploadButton: React.FC<Props> = ({
  token, apiKey, disabled, onResult, onError, uploading, resetSignal, setUploading,
}) => {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.value = '';
    }
  }, [resetSignal]);

  const handleChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (inputRef.current) inputRef.current.value = '';
    if (!file) return;
    setUploading(true);
    const res = await uploadFile(file, token, apiKey);
    setUploading(false);
    if (res.ok) onResult(res.result);
    else onError(res.error);
  };

  const isDisabled = disabled || uploading;

  return (
    <>
      <input ref={inputRef} type="file" accept={ACCEPTED_UPLOAD_TYPES} style={{ display: 'none' }} onChange={handleChange} />
      <button
        type="button"
        disabled={isDisabled}
        aria-label={uploading ? 'Uploading attachment' : 'Attach a file'}
        title={uploading ? 'Uploading attachment...' : 'Attach file (.hex, .txt, .xml, .log, .pdf, .docx)'}
        onClick={() => inputRef.current?.click()}
        style={{
          height: 40,
          width: 40,
          borderRadius: 14,
          background: uploading ? 'var(--chat-primary-soft)' : 'transparent',
          border: '1px solid var(--chat-border)',
          color: uploading ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
          cursor: isDisabled ? 'not-allowed' : 'pointer',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 16,
          transition: 'color 0.15s ease, border-color 0.15s ease, background 0.15s ease, transform 0.15s ease',
          flexShrink: 0,
          boxShadow: uploading ? '0 0 0 4px var(--chat-primary-soft)' : 'none',
        }}
        onMouseEnter={(event) => {
          if (!isDisabled) {
            event.currentTarget.style.color = 'var(--ion-color-primary)';
            event.currentTarget.style.borderColor = 'var(--ion-color-primary)';
            event.currentTarget.style.background = 'var(--chat-primary-soft)';
            event.currentTarget.style.transform = 'translateY(-1px)';
          }
        }}
        onMouseLeave={(event) => {
          if (!uploading) {
            event.currentTarget.style.color = 'var(--chat-muted)';
            event.currentTarget.style.borderColor = 'var(--chat-border)';
            event.currentTarget.style.background = 'transparent';
            event.currentTarget.style.transform = 'translateY(0)';
          }
        }}
      >
        {uploading
          ? <span style={{ width: 14, height: 14, border: '2px solid rgba(90,90,245,0.16)', borderTopColor: 'var(--ion-color-primary)', borderRadius: '50%', display: 'inline-block', animation: 'spin 0.7s linear infinite' }} />
          : <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
        }
      </button>
    </>
  );
};
