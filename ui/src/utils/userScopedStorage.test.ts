import {
  clearComposerDraft,
  loadComposerDraft,
  loadConversationMetaMap,
  saveComposerDraft,
  saveConversationMetaMap,
} from '../chat/chatFeatureUtils';
import {
  getUserActiveConversationStorageKey,
  getUserActiveHistoryStorageKey,
  getUserApiKeyStorageKey,
  getUserComposerDraftStorageKey,
  getUserConversationMetaStorageKey,
  LEGACY_ACTIVE_CONVERSATION_STORAGE_KEY,
  LEGACY_ACTIVE_HISTORY_STORAGE_KEY,
  LEGACY_API_KEY_STORAGE_KEY,
  LEGACY_CONVERSATION_META_STORAGE_KEY,
  LEGACY_NEW_DRAFT_STORAGE_KEY,
  migrateLegacyApiKey,
  migrateLegacyChatStorage,
} from './userScopedStorage';

describe('userScopedStorage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('stores conversation meta and drafts under user-scoped keys', () => {
    saveConversationMetaMap('user-1', {
      'conv-1': {
        pinned: true,
        category: 'security',
        manualTitle: false,
        autoRetitled: true,
      },
    });
    saveComposerDraft('user-1', 'conv-1', {
      inputText: 'Explain replay protection',
      attachments: [],
      activePreset: 'security',
    });

    expect(localStorage.getItem(getUserConversationMetaStorageKey('user-1'))).not.toBeNull();
    expect(localStorage.getItem(getUserComposerDraftStorageKey('user-1', 'conv-1'))).not.toBeNull();
    expect(loadConversationMetaMap('user-1')).toEqual({
      'conv-1': {
        pinned: true,
        category: 'security',
        manualTitle: false,
        autoRetitled: true,
      },
    });
    expect(loadComposerDraft('user-1', 'conv-1')).toEqual({
      inputText: 'Explain replay protection',
      attachments: [],
      activePreset: 'security',
    });

    clearComposerDraft('user-1', 'conv-1');
    expect(loadComposerDraft('user-1', 'conv-1')).toBeNull();
  });

  it('migrates legacy chat caches into the current user namespace without overwriting scoped data', () => {
    localStorage.setItem(LEGACY_ACTIVE_CONVERSATION_STORAGE_KEY, 'legacy-conv');
    localStorage.setItem(LEGACY_ACTIVE_HISTORY_STORAGE_KEY, '{"history":"legacy"}');
    localStorage.setItem(LEGACY_CONVERSATION_META_STORAGE_KEY, JSON.stringify({
      'legacy-conv': {
        pinned: true,
        category: 'incident',
      },
    }));
    localStorage.setItem(LEGACY_NEW_DRAFT_STORAGE_KEY, JSON.stringify({
      inputText: 'Legacy new draft',
      attachments: [],
      activePreset: null,
    }));
    localStorage.setItem('dlms_chat_draft_legacy-conv', JSON.stringify({
      inputText: 'Legacy conversation draft',
      attachments: [],
      activePreset: 'incident',
    }));
    localStorage.setItem(getUserActiveConversationStorageKey('user-1'), 'scoped-conv');

    migrateLegacyChatStorage('user-1');

    expect(localStorage.getItem(getUserActiveConversationStorageKey('user-1'))).toBe('scoped-conv');
    expect(localStorage.getItem(getUserActiveHistoryStorageKey('user-1'))).toBe('{"history":"legacy"}');
    expect(loadConversationMetaMap('user-1')).toEqual({
      'legacy-conv': {
        pinned: true,
        category: 'incident',
        manualTitle: false,
        autoRetitled: false,
      },
    });
    expect(loadComposerDraft('user-1', null)).toEqual({
      inputText: 'Legacy new draft',
      attachments: [],
      activePreset: null,
    });
    expect(loadComposerDraft('user-1', 'legacy-conv')).toEqual({
      inputText: 'Legacy conversation draft',
      attachments: [],
      activePreset: 'incident',
    });
  });

  it('migrates the legacy API key once per user without overwriting a newer scoped value', () => {
    localStorage.setItem(LEGACY_API_KEY_STORAGE_KEY, 'legacy-api-key');

    migrateLegacyApiKey('user-1');
    expect(localStorage.getItem(getUserApiKeyStorageKey('user-1'))).toBe('legacy-api-key');

    localStorage.setItem(getUserApiKeyStorageKey('user-1'), 'scoped-api-key');
    migrateLegacyApiKey('user-1');
    expect(localStorage.getItem(getUserApiKeyStorageKey('user-1'))).toBe('scoped-api-key');
  });
});
