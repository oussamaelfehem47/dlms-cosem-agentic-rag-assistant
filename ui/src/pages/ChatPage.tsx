import React, { useCallback, useEffect, useRef, useState } from 'react';
import { IonContent, IonIcon, IonPage } from '@ionic/react';
import { chevronDownOutline } from 'ionicons/icons';
import { ConversationSidebar } from '../components/ConversationSidebar';
import { ToolTestPanel } from '../components/ToolTestPanel';
import { useApiKey } from '../hooks/useApiKey';
import { useConversations } from '../hooks/useConversations';
import { useMcpStatus } from '../hooks/useMcpStatus';
import { useStreamingDecode } from '../hooks/useStreamingDecode';
import { ChatComposer } from '../chat/ChatComposer';
import { ChatEmptyState } from '../chat/ChatEmptyState';
import { ChatHeader } from '../chat/ChatHeader';
import { ChatMessageList } from '../chat/ChatMessageList';
import { buildRequestArtifacts, buildSubmittedInput, dbMessagesToHistory } from '../chat/chatUtils';
import {
  clearComposerDraft,
  detectConversationCategory,
  detectHistoryCategory,
  downloadConversationExport,
  loadComposerDraft,
  loadConversationMetaMap,
  LocalConversationMetaMap,
  resolveSuggestedEndpoint,
  saveComposerDraft,
  saveConversationMetaMap,
  suggestAutoRenameTitle,
  suggestConversationTitle,
} from '../chat/chatFeatureUtils';
import { useChatComposer } from '../chat/useChatComposer';
import { useChatShellState } from '../chat/useChatShellState';
import { ConversationEntry } from '../types';
import {
  getUserActiveConversationStorageKey,
  getUserActiveHistoryStorageKey,
  migrateLegacyChatStorage,
} from '../utils/userScopedStorage';

interface StoredHistoryPayload {
  conversationId: string;
  history: Array<Omit<ConversationEntry, 'timestamp'> & { timestamp: string }>;
}

function serializeHistory(
  conversationId: string,
  history: ConversationEntry[]
): string {
  const payload: StoredHistoryPayload = {
    conversationId,
    history: history.map((entry) => ({
      ...entry,
      timestamp: entry.timestamp.toISOString(),
    })),
  };

  return JSON.stringify(payload);
}

function parseStoredHistory(rawValue: string | null, conversationId: string): ConversationEntry[] {
  if (!rawValue) return [];

  try {
    const parsed = JSON.parse(rawValue) as StoredHistoryPayload;
    if (parsed.conversationId !== conversationId || !Array.isArray(parsed.history)) {
      return [];
    }

    return parsed.history.map((entry) => ({
      ...entry,
      timestamp: new Date(entry.timestamp),
    }));
  } catch {
    return [];
  }
}

interface Props {
  token: string;
  userId: string;
  onLogout: () => Promise<void> | void;
  onOpenAccountSettings: () => void;
  refreshToken: () => Promise<boolean>;
  showAdminPanelLink?: boolean;
  onOpenAdminPanel?: () => void;
}

export const ChatPage: React.FC<Props> = ({
  token,
  userId,
  onLogout,
  onOpenAccountSettings,
  refreshToken,
  showAdminPanelLink = false,
  onOpenAdminPanel,
}) => {
  const initialStorageRef = useRef<{
    activeConversationId: string | null;
    conversationMeta: LocalConversationMetaMap;
  } | null>(null);

  if (!initialStorageRef.current) {
    migrateLegacyChatStorage(userId);
    initialStorageRef.current = {
      activeConversationId: localStorage.getItem(getUserActiveConversationStorageKey(userId)),
      conversationMeta: loadConversationMetaMap(userId),
    };
  }

  const activeConversationStorageKey = getUserActiveConversationStorageKey(userId);
  const activeHistoryStorageKey = getUserActiveHistoryStorageKey(userId);
  const initialConversationId = initialStorageRef.current.activeConversationId;
  const { apiKey } = useApiKey(userId);
  const {
    themeMode,
    toggleTheme,
    sidebarOpen,
    setSidebarOpen,
    toolPanelOpen,
    setToolPanelOpen,
    showSamples,
    setShowSamples,
    showScrollBtn,
    setShowScrollBtn,
    closeTransientPanels,
  } = useChatShellState();
  const {
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
    clearAll,
    applyDraft,
    getDraftPayload,
    streamingInput,
    streamingInputClass,
    setStreamingPreview,
  } = useChatComposer();
  const [conversationId, setConversationId] = useState<string | null>(initialConversationId);
  const [conversationMeta, setConversationMeta] = useState<LocalConversationMetaMap>(
    () => initialStorageRef.current?.conversationMeta ?? {},
  );
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [isRestoringConversation, setIsRestoringConversation] = useState(() =>
    Boolean(initialConversationId)
  );
  const [draftHydratedFor, setDraftHydratedFor] = useState<string | null | undefined>(undefined);
  const activeConversationIdRef = useRef<string | null>(initialConversationId);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const chatContainerRef = useRef<HTMLIonContentElement>(null);
  const restoredConversationRef = useRef(false);

  const {
    conversations,
    activeConv,
    createConversation,
    openConversation,
    saveMessage,
    renameConversation,
    deleteConversation,
  } = useConversations(token);

  const {
    isStreaming,
    streamingText,
    usedFallback,
    artifactResults,
    explanationMode,
    toolProvenance,
    orchestrationMode,
    plannerUsed,
    toolTrace,
    plannerFallbackReason,
    error,
    sessionId,
    lastIntent,
    strategyMetadata,
    history,
    setHistory,
    submit,
    reset,
    clearHistory,
    detectInputClass,
  } = useStreamingDecode(apiKey, token, refreshToken, undefined, saveMessage);

  const handleFeedback = useCallback(async (entryId: string, value: 'like' | 'dislike') => {
    const entry = history.find(e => e.id === entryId);
    if (!token || !entry) return;
    try {
      await fetch('/api/admin/reflection/feedback', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          conversationId: activeConv?.id || null,
          feedback: value,
          intent: entry.intent || null,
          inputClass: entry.inputClass?.toUpperCase() || null,
          responseSnapshot: entry.explanation?.slice(0, 500) || null,
          modelName: null,
        }),
      });
    } catch {
      // Fire-and-forget: silently ignore network errors
    }
  }, [history, token, activeConv]);

  const mcpStatus = useMcpStatus(apiKey, token);

  useEffect(() => {
    closeTransientPanels();
  }, [closeTransientPanels, token]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [history, isStreaming, streamingText]);

  useEffect(() => {
    let mounted = true;
    let scrollEl: HTMLElement | null = null;

    const handleScroll = () => {
      if (!scrollEl || !mounted) return;
      const distance = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight;
      setShowScrollBtn(distance > 400);
    };

    const attach = async () => {
      if (!chatContainerRef.current) return;
      scrollEl = await chatContainerRef.current.getScrollElement();
      if (!mounted || !scrollEl) return;
      scrollEl.addEventListener('scroll', handleScroll);
      handleScroll();
    };

    void attach();

    return () => {
      mounted = false;
      scrollEl?.removeEventListener('scroll', handleScroll);
    };
  }, [setShowScrollBtn]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key === '/') {
        event.preventDefault();
        setToolPanelOpen((open) => !open);
      }
    };

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [setToolPanelOpen]);

  const setActiveConversationId = useCallback((nextConversationId: string | null) => {
    activeConversationIdRef.current = nextConversationId;
    setConversationId(nextConversationId);

    if (nextConversationId) {
      localStorage.setItem(activeConversationStorageKey, nextConversationId);
      return;
    }

    localStorage.removeItem(activeConversationStorageKey);
    localStorage.removeItem(activeHistoryStorageKey);
  }, [activeConversationStorageKey, activeHistoryStorageKey]);

  const clearDraftAttachmentsForConversation = useCallback((targetConversationId: string | null) => {
    const draft = loadComposerDraft(userId, targetConversationId);
    if (!draft || draft.attachments.length === 0) {
      return;
    }

    const sanitizedDraft = {
      ...draft,
      attachments: [],
    };

    if (!sanitizedDraft.inputText.trim() && !sanitizedDraft.activePreset) {
      clearComposerDraft(userId, targetConversationId);
      return;
    }

    saveComposerDraft(userId, targetConversationId, sanitizedDraft);
  }, [userId]);

  const handleOpenConversation = useCallback(async (
    convId: string,
    options?: { preserveCachedHistoryOnFailure?: boolean }
  ) => {
    clearDraftAttachmentsForConversation(convId);
    clearAttachments();
    setIsRestoringConversation(true);
    setDeleteError(null);
    setActiveConversationId(convId);
    const cachedHistory = parseStoredHistory(
      localStorage.getItem(activeHistoryStorageKey),
      convId,
    );
    const conversation = await openConversation(convId);
    if (!conversation) {
      if (options?.preserveCachedHistoryOnFailure && cachedHistory.length > 0) {
        setHistory(cachedHistory);
        setIsRestoringConversation(false);
        return;
      }
      clearDraftAttachmentsForConversation(null);
      setActiveConversationId(null);
      setHistory([]);
      setIsRestoringConversation(false);
      return;
    }
    const serverHistory = dbMessagesToHistory(conversation.messages);
    setActiveConversationId(conversation.id);
    const nextHistory = cachedHistory.length > serverHistory.length ? cachedHistory : serverHistory;
    setHistory(nextHistory);
    setConversationMeta((current) => ({
      ...current,
      [conversation.id]: {
        pinned: current[conversation.id]?.pinned || false,
        category: detectHistoryCategory(nextHistory),
        manualTitle: current[conversation.id]?.manualTitle || false,
        autoRetitled: current[conversation.id]?.autoRetitled || false,
      },
    }));
    setSidebarOpen(false);
    setIsRestoringConversation(false);
  }, [activeHistoryStorageKey, clearAttachments, clearDraftAttachmentsForConversation, openConversation, setActiveConversationId, setHistory, setSidebarOpen]);

  const handleNewConversation = useCallback(() => {
    clearDraftAttachmentsForConversation(null);
    setDeleteError(null);
    reset();
    clearHistory();
    setActiveConversationId(null);
    setIsRestoringConversation(false);
    clearAll();
    setSidebarOpen(false);
  }, [clearAll, clearDraftAttachmentsForConversation, clearHistory, reset, setActiveConversationId, setSidebarOpen]);

  const handleClear = useCallback(() => {
    clearDraftAttachmentsForConversation(null);
    setDeleteError(null);
    reset();
    clearHistory();
    setActiveConversationId(null);
    setIsRestoringConversation(false);
    clearAll();
  }, [clearAll, clearDraftAttachmentsForConversation, clearHistory, reset, setActiveConversationId]);

  const handleDeleteConversation = useCallback(async (convId: string) => {
    setDeleteError(null);
    const deleted = await deleteConversation(convId);
    if (!deleted) {
      setDeleteError('Could not delete this conversation. Refresh the backend and try again.');
      return;
    }
    clearComposerDraft(userId, convId);
    setConversationMeta((current) => {
      const next = { ...current };
      delete next[convId];
      return next;
    });

    if (conversationId !== convId) {
      return;
    }

    reset();
    clearHistory();
    clearDraftAttachmentsForConversation(null);
    clearAll();
    setActiveConversationId(null);
    setIsRestoringConversation(false);
  }, [clearAll, clearDraftAttachmentsForConversation, clearHistory, conversationId, deleteConversation, reset, setActiveConversationId, userId]);

  const handleSuggestionClick = useCallback((suggestion: string) => {
    setDeleteError(null);
    setInputText(suggestion);
    textareaRef.current?.focus();
  }, [setInputText]);

  const handleSampleSelect = useCallback((sample: Parameters<typeof loadSample>[0]) => {
    loadSample(sample);
    setShowSamples(false);
    textareaRef.current?.focus();
  }, [loadSample, setShowSamples]);

  const handlePresetSelect = useCallback((preset: 'security' | 'decode' | 'incident', starter: string) => {
    applyPreset(preset, starter);
    setShowSamples(false);
    textareaRef.current?.focus();
  }, [applyPreset, setShowSamples]);

  const handleTogglePin = useCallback((convId: string) => {
    setConversationMeta((current) => ({
      ...current,
      [convId]: {
        pinned: !current[convId]?.pinned,
        category: current[convId]?.category || 'general',
        manualTitle: current[convId]?.manualTitle || false,
        autoRetitled: current[convId]?.autoRetitled || false,
      },
    }));
  }, []);

  const handleRenameConversation = useCallback(async (convId: string, title: string) => {
    const renamed = await renameConversation(convId, title);
    if (!renamed) return false;

    setConversationMeta((current) => ({
      ...current,
      [convId]: {
        pinned: current[convId]?.pinned || false,
        category: current[convId]?.category || 'general',
        manualTitle: true,
        autoRetitled: current[convId]?.autoRetitled || false,
      },
    }));

    return true;
  }, [renameConversation]);

  const handleExportConversation = useCallback((convId: string, format: 'markdown' | 'text') => {
    void (async () => {
      const conversation = conversations.find((item) => item.id === convId);
      if (!conversation) return;

      if (convId === conversationId && history.length > 0) {
        downloadConversationExport(conversation.title, history, format);
        return;
      }

      const loaded = await openConversation(convId);
      if (!loaded) return;
      downloadConversationExport(loaded.title, dbMessagesToHistory(loaded.messages), format);
    })();
  }, [conversationId, conversations, history, openConversation]);

  const handleSubmit = useCallback(async () => {
    if (isStreaming) return;

    const userInput = buildSubmittedInput(inputText, attachments);
    const requestArtifacts = buildRequestArtifacts(attachments);
    if (!userInput.trim()) return;

    setShowSamples(false);
    const inferredCategory = detectConversationCategory(
      inputText.trim() || userInput,
      attachments[0]?.inputClass || detectInputClass(userInput),
    );

    let currentConversationId =
      conversationId
      || activeConversationIdRef.current
      || localStorage.getItem(activeConversationStorageKey);

    const candidateTitle = suggestConversationTitle(
      inputText.trim() || userInput,
      attachments,
      inferredCategory,
    );

    if (!currentConversationId) {
      currentConversationId = await createConversation(candidateTitle || 'New Conversation');
      if (currentConversationId) {
        setActiveConversationId(currentConversationId);
        setConversationMeta((current) => ({
          ...current,
          [currentConversationId as string]: {
            pinned: current[currentConversationId as string]?.pinned || false,
            category: inferredCategory,
            manualTitle: current[currentConversationId as string]?.manualTitle || false,
            autoRetitled: current[currentConversationId as string]?.autoRetitled || false,
          },
        }));
      }
    } else {
      const currentConversationTitle = activeConv?.title
        || conversations.find((item) => item.id === currentConversationId)?.title
        || '';
      const currentMeta = conversationMeta[currentConversationId];
      const autoRenameTitle = suggestAutoRenameTitle(
        currentConversationTitle,
        inputText.trim() || userInput,
        attachments,
        inferredCategory,
        {
          manualTitle: currentMeta?.manualTitle,
          autoRetitled: currentMeta?.autoRetitled,
        },
      );

      if (autoRenameTitle) {
        const renamed = await renameConversation(currentConversationId, autoRenameTitle);
        if (renamed) {
          setConversationMeta((current) => ({
            ...current,
            [currentConversationId as string]: {
              pinned: current[currentConversationId as string]?.pinned || false,
              category: current[currentConversationId as string]?.category || inferredCategory,
              manualTitle: current[currentConversationId as string]?.manualTitle || false,
              autoRetitled: true,
            },
          }));
        }
      }
    }

    const nextInputClass = attachments.length === 1
      ? attachments[0].inputClass
      : requestArtifacts.length > 1
        ? 'query'
        : detectInputClass(userInput);

    setInputText('');
    setActivePreset(null);
    setStreamingPreview(
      inputText.trim() || userInput,
      nextInputClass,
    );
    const suggestedEndpoint = resolveSuggestedEndpoint(attachments);
    const sentSuccessfully = await submit(
      userInput,
      sessionId || undefined,
      currentConversationId || undefined,
      suggestedEndpoint,
      requestArtifacts,
    );

    if (sentSuccessfully) {
      clearAttachments();
    }
  }, [
    activeConv?.title,
    attachments,
    clearAttachments,
    conversationId,
    conversationMeta,
    conversations,
    createConversation,
    detectInputClass,
    inputText,
    isStreaming,
    renameConversation,
    sessionId,
    setActivePreset,
    setActiveConversationId,
    setInputText,
    setConversationMeta,
    setShowSamples,
    setStreamingPreview,
    submit,
    activeConversationStorageKey,
  ]);

  const handleComposerKeyDown = useCallback((event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void handleSubmit();
    }
  }, [handleSubmit]);

  useEffect(() => {
    if (!conversationId) return;
    localStorage.setItem(activeHistoryStorageKey, serializeHistory(conversationId, history));
  }, [activeHistoryStorageKey, conversationId, history]);

  useEffect(() => {
    if (!conversationId || history.length === 0) return;
    const category = detectHistoryCategory(history);
    setConversationMeta((current) => ({
      ...current,
      [conversationId]: {
        pinned: current[conversationId]?.pinned || false,
        category,
        manualTitle: current[conversationId]?.manualTitle || false,
        autoRetitled: current[conversationId]?.autoRetitled || false,
      },
    }));
  }, [conversationId, history]);

  useEffect(() => {
    saveConversationMetaMap(userId, conversationMeta);
  }, [conversationMeta, userId]);

  useEffect(() => {
    if (restoredConversationRef.current) return;

    const storedConversationId = localStorage.getItem(activeConversationStorageKey);
    if (!storedConversationId) {
      restoredConversationRef.current = true;
      setIsRestoringConversation(false);
      return;
    }

    restoredConversationRef.current = true;
    setActiveConversationId(storedConversationId);

    const cachedHistory = parseStoredHistory(
      localStorage.getItem(activeHistoryStorageKey),
      storedConversationId,
    );
    if (cachedHistory.length > 0) {
      setHistory(cachedHistory);
    }

    void handleOpenConversation(storedConversationId, { preserveCachedHistoryOnFailure: true });
  }, [activeConversationStorageKey, activeHistoryStorageKey, handleOpenConversation, setActiveConversationId, setHistory]);

  useEffect(() => {
    applyDraft(loadComposerDraft(userId, conversationId));
    setDraftHydratedFor(conversationId);
  }, [applyDraft, conversationId, userId]);

  useEffect(() => {
    if (draftHydratedFor !== conversationId) {
      return;
    }
    const draft = getDraftPayload();
    const isEmptyDraft = !draft.inputText.trim() && draft.attachments.length === 0 && !draft.activePreset;
    if (isEmptyDraft) {
      clearComposerDraft(userId, conversationId);
      return;
    }
    saveComposerDraft(userId, conversationId, draft);
  }, [attachments, activePreset, conversationId, draftHydratedFor, getDraftPayload, inputText, userId]);

  const isEmpty = history.length === 0 && !isStreaming && !conversationId && !isRestoringConversation;
  const canSend = (inputText.trim().length > 0 || attachments.some((item) => item.text.trim().length > 0)) && !isStreaming;
  const showRestoringState = isRestoringConversation && history.length === 0;
  const composerCategory = detectConversationCategory(
    inputText,
    attachments[0]?.inputClass || 'query',
  );
  const activeConversationTitle = activeConv?.title
    || conversations.find((item) => item.id === conversationId)?.title
    || null;

  return (
    <IonPage style={{ background: 'var(--chat-bg)' }}>
      <ConversationSidebar
        conversations={conversations}
        activeId={conversationId}
        conversationMeta={conversationMeta}
        deleteError={deleteError}
        onDismissDeleteError={() => setDeleteError(null)}
        onSelect={handleOpenConversation}
        onNew={handleNewConversation}
        onRename={handleRenameConversation}
        onDelete={handleDeleteConversation}
        onTogglePin={handleTogglePin}
        onExport={handleExportConversation}
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        showAdminPanelLink={showAdminPanelLink}
        onOpenAdminPanel={onOpenAdminPanel}
      />

      {toolPanelOpen && (
        <ToolTestPanel
          apiKey={apiKey}
          jwtToken={token}
          onClose={() => setToolPanelOpen(false)}
        />
      )}

      <ChatHeader
        themeMode={themeMode}
        mcpStatus={mcpStatus.status}
        activeConversationTitle={activeConversationTitle}
        onRefreshMcp={mcpStatus.refresh}
        onOpenSidebar={() => setSidebarOpen(true)}
        onToggleToolPanel={() => setToolPanelOpen((open) => !open)}
        onToggleTheme={toggleTheme}
        onOpenAccountSettings={onOpenAccountSettings}
        onClear={handleClear}
        onLogout={onLogout}
        disableClear={isEmpty}
      />

      <IonContent style={{ '--background': 'var(--chat-bg)' }} ref={chatContainerRef}>
        {showRestoringState ? (
          <div
            data-testid="chat-restoring-state"
            aria-live="polite"
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              minHeight: 'calc(100vh - 56px)',
              padding: '32px 16px 176px',
              color: 'var(--chat-muted)',
              fontSize: 14,
            }}
          >
            Restoring conversation...
          </div>
        ) : isEmpty ? (
          <ChatEmptyState onSuggestionClick={handleSuggestionClick} />
        ) : (
          <ChatMessageList
            history={history}
            isStreaming={isStreaming}
            streamingInput={streamingInput}
            streamingInputClass={streamingInputClass}
            streamingText={streamingText}
            streamingArtifactResults={artifactResults}
            streamingIntent={lastIntent}
            streamingStrategyMetadata={strategyMetadata}
            usedFallback={usedFallback}
            streamingExplanationMode={explanationMode}
            streamingToolProvenance={toolProvenance}
            streamingOrchestrationMode={orchestrationMode}
            streamingPlannerUsed={plannerUsed}
            streamingToolTrace={toolTrace}
            streamingPlannerFallbackReason={plannerFallbackReason}
            messagesEndRef={messagesEndRef}
            onFeedback={handleFeedback}
          />
        )}

        {showScrollBtn && !isEmpty && (
          <button
            onClick={() => messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })}
            style={{
              position: 'fixed',
              bottom: 'var(--scroll-btn-bottom)',
              right: 24,
              width: 36,
              height: 36,
              borderRadius: '50%',
              border: '1px solid var(--chat-border)',
              background: 'var(--chat-surface)',
              color: 'var(--chat-muted)',
              cursor: 'pointer',
              zIndex: 50,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: 'var(--chat-shadow)',
              animation: 'fadeIn 0.2s ease',
            }}
          >
            <IonIcon icon={chevronDownOutline} style={{ fontSize: 16 }} />
          </button>
        )}

        <ChatComposer
          token={token}
          apiKey={apiKey}
          isStreaming={isStreaming}
          inputText={inputText}
          attachments={attachments}
          attachmentResetSignal={attachmentResetSignal}
          uploadError={uploadError}
          streamError={error}
          uploading={uploading}
          showSamples={showSamples}
          canSend={canSend}
          activePreset={activePreset}
          composerCategory={composerCategory}
          textareaRef={textareaRef}
          onInputChange={setInputText}
          onKeyDown={handleComposerKeyDown}
          onToggleSamples={() => setShowSamples((open) => !open)}
          onSampleSelect={handleSampleSelect}
          onPresetSelect={handlePresetSelect}
          onAttachmentResult={handleAttachmentResult}
          onUploadError={setUploadError}
          setUploading={setUploading}
          onRemoveAttachment={removeAttachment}
          onMoveAttachment={moveAttachment}
          onSubmit={() => void handleSubmit()}
        />
      </IonContent>
    </IonPage>
  );
};
