const USER_STORAGE_PREFIX = 'dlms_user_';

export const LEGACY_ACTIVE_CONVERSATION_STORAGE_KEY = 'dlms_active_conversation_id';
export const LEGACY_ACTIVE_HISTORY_STORAGE_KEY = 'dlms_active_conversation_history';
export const LEGACY_CONVERSATION_META_STORAGE_KEY = 'dlms_local_conversation_meta';
export const LEGACY_NEW_DRAFT_STORAGE_KEY = 'dlms_chat_draft_new';
export const LEGACY_DRAFT_PREFIX = 'dlms_chat_draft_';
export const LEGACY_API_KEY_STORAGE_KEY = 'dlms_api_key';

function userStoragePrefix(userId: string): string {
  return `${USER_STORAGE_PREFIX}${userId}_`;
}

export function getUserActiveConversationStorageKey(userId: string): string {
  return `${userStoragePrefix(userId)}active_conversation_id`;
}

export function getUserActiveHistoryStorageKey(userId: string): string {
  return `${userStoragePrefix(userId)}active_conversation_history`;
}

export function getUserConversationMetaStorageKey(userId: string): string {
  return `${userStoragePrefix(userId)}conversation_meta`;
}

export function getUserComposerDraftStorageKey(
  userId: string,
  conversationId: string | null,
): string {
  return conversationId
    ? `${userStoragePrefix(userId)}chat_draft_${conversationId}`
    : `${userStoragePrefix(userId)}chat_draft_new`;
}

export function getUserApiKeyStorageKey(userId: string): string {
  return `${userStoragePrefix(userId)}api_key`;
}

function migrateValueIfMissing(targetKey: string, legacyKey: string): void {
  if (localStorage.getItem(targetKey) !== null) {
    return;
  }

  const legacyValue = localStorage.getItem(legacyKey);
  if (legacyValue !== null) {
    localStorage.setItem(targetKey, legacyValue);
  }
}

export function migrateLegacyChatStorage(userId: string): void {
  if (!userId) return;

  migrateValueIfMissing(
    getUserActiveConversationStorageKey(userId),
    LEGACY_ACTIVE_CONVERSATION_STORAGE_KEY,
  );
  migrateValueIfMissing(
    getUserActiveHistoryStorageKey(userId),
    LEGACY_ACTIVE_HISTORY_STORAGE_KEY,
  );
  migrateValueIfMissing(
    getUserConversationMetaStorageKey(userId),
    LEGACY_CONVERSATION_META_STORAGE_KEY,
  );
  migrateValueIfMissing(
    getUserComposerDraftStorageKey(userId, null),
    LEGACY_NEW_DRAFT_STORAGE_KEY,
  );

  const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
    .filter((key): key is string => Boolean(key));

  for (const key of keys) {
    if (!key.startsWith(LEGACY_DRAFT_PREFIX) || key === LEGACY_NEW_DRAFT_STORAGE_KEY) {
      continue;
    }

    const conversationId = key.slice(LEGACY_DRAFT_PREFIX.length);
    migrateValueIfMissing(getUserComposerDraftStorageKey(userId, conversationId), key);
  }
}

export function migrateLegacyApiKey(userId: string): void {
  if (!userId) return;
  migrateValueIfMissing(getUserApiKeyStorageKey(userId), LEGACY_API_KEY_STORAGE_KEY);
}
