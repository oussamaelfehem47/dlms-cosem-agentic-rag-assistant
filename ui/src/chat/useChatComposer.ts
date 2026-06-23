import { useCallback, useState } from 'react';
import { UploadResult } from '../components/UploadButton';
import { InputClass } from '../types';
import { SampleData } from './chatConfig';
import { ComposerDraft, ComposerPreset } from './chatFeatureUtils';

export function useChatComposer() {
  const [inputText, setInputText] = useState('');
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [attachments, setAttachments] = useState<UploadResult[]>([]);
  const [attachmentResetSignal, setAttachmentResetSignal] = useState(0);
  const [activePreset, setActivePreset] = useState<ComposerPreset | null>(null);
  const [streamingInput, setStreamingInput] = useState('');
  const [streamingInputClass, setStreamingInputClass] = useState<InputClass>('query');

  const handleAttachmentResult = useCallback((result: UploadResult) => {
    setAttachments((current) => [...current, result]);
    setUploadError(null);
  }, []);

  const removeAttachment = useCallback((index: number) => {
    setAttachments((current) => current.filter((_, currentIndex) => currentIndex !== index));
  }, []);

  const moveAttachment = useCallback((index: number, direction: 'up' | 'down') => {
    setAttachments((current) => {
      const nextIndex = direction === 'up' ? index - 1 : index + 1;
      if (nextIndex < 0 || nextIndex >= current.length) return current;
      const next = [...current];
      const [item] = next.splice(index, 1);
      next.splice(nextIndex, 0, item);
      return next;
    });
  }, []);

  const clearAttachments = useCallback(() => {
    setUploading(false);
    setUploadError(null);
    setAttachments([]);
    setAttachmentResetSignal((current) => current + 1);
  }, []);

  const loadSample = useCallback((sample: SampleData) => {
    setInputText(sample.text);
    setAttachments([]);
    setActivePreset(
      sample.category === 'security' ||
      sample.category === 'decode' ||
      sample.category === 'incident'
        ? sample.category
        : null
    );
    setUploadError(null);
  }, []);

  const applyPreset = useCallback((preset: ComposerPreset, starter: string) => {
    setActivePreset(preset);
    setInputText((current) => {
      if (!current.trim()) return starter;
      if (current.startsWith(starter)) return current;
      return `${starter}${current}`;
    });
  }, []);

  const clearDraft = useCallback(() => {
    setInputText('');
    setAttachments([]);
    setActivePreset(null);
    setUploadError(null);
  }, []);

  const clearAll = useCallback(() => {
    setInputText('');
    clearAttachments();
    setActivePreset(null);
    setStreamingInput('');
    setStreamingInputClass('query');
  }, [clearAttachments]);

  const applyDraft = useCallback((draft: ComposerDraft | null) => {
    setInputText(draft?.inputText || '');
    setAttachments(draft?.attachments || []);
    setActivePreset(draft?.activePreset || null);
    setUploadError(null);
  }, []);

  const getDraftPayload = useCallback((): ComposerDraft => ({
    inputText,
    attachments,
    activePreset,
  }), [activePreset, attachments, inputText]);

  const setStreamingPreview = useCallback((input: string, inputClass: InputClass) => {
    setStreamingInput(input);
    setStreamingInputClass(inputClass);
  }, []);

  return {
    inputText,
    setInputText,
    uploadError,
    setUploadError,
    uploading,
    setUploading,
    attachments,
    attachmentResetSignal,
    activePreset,
    setActivePreset,
    handleAttachmentResult,
    removeAttachment,
    moveAttachment,
    clearAttachments,
    loadSample,
    applyPreset,
    clearDraft,
    clearAll,
    applyDraft,
    getDraftPayload,
    streamingInput,
    streamingInputClass,
    setStreamingPreview,
  };
}
