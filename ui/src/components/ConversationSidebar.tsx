import React, { useEffect, useMemo, useRef, useState } from 'react';
import { IonIcon } from '@ionic/react';
import {
  addOutline,
  chatbubbleEllipsesOutline,
  closeOutline,
  createOutline,
  downloadOutline,
  searchOutline,
  settingsOutline,
  star,
  starOutline,
  trashOutline,
} from 'ionicons/icons';
import { Conversation } from '../hooks/useConversations';
import {
  ExportFormat,
  LocalConversationMetaMap,
  UiConversationCategory,
} from '../chat/chatFeatureUtils';
import { CATEGORY_ICONS } from '../chat/chatConfig';

interface Props {
  conversations: Conversation[];
  activeId: string | null;
  conversationMeta: LocalConversationMetaMap;
  deleteError?: string | null;
  onDismissDeleteError?: () => void;
  onSelect: (convId: string) => void;
  onNew: () => void;
  onDelete: (convId: string) => Promise<void> | void;
  onRename: (convId: string, title: string) => Promise<boolean>;
  onTogglePin: (convId: string) => void;
  onExport: (convId: string, format: ExportFormat) => void;
  isOpen: boolean;
  onClose: () => void;
  showAdminPanelLink?: boolean;
  onOpenAdminPanel?: () => void;
}

type SidebarFilter = 'all' | 'pinned' | UiConversationCategory;

const FILTERS: Array<{ id: SidebarFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'pinned', label: 'Pinned' },
  { id: 'security', label: 'Security' },
  { id: 'decode', label: 'Decode' },
  { id: 'incident', label: 'Incidents' },
];

export const ConversationSidebar: React.FC<Props> = ({
  conversations,
  activeId,
  conversationMeta,
  deleteError,
  onDismissDeleteError,
  onSelect,
  onNew,
  onDelete,
  onRename,
  onTogglePin,
  onExport,
  isOpen,
  onClose,
  showAdminPanelLink = false,
  onOpenAdminPanel,
}) => {
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [filter, setFilter] = useState<SidebarFilter>('all');
  const renameInputRef = useRef<HTMLInputElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (renamingId) renameInputRef.current?.focus();
  }, [renamingId]);

  useEffect(() => {
    if (!isOpen) return;
    const timer = window.setTimeout(() => searchInputRef.current?.focus(), 180);
    return () => window.clearTimeout(timer);
  }, [isOpen]);

  const startRename = (conversation: Conversation, event: React.MouseEvent) => {
    event.stopPropagation();
    setRenamingId(conversation.id);
    setRenameValue(conversation.title);
  };

  const commitRename = async (convId: string) => {
    const trimmed = renameValue.trim();
    if (trimmed) await onRename(convId, trimmed);
    setRenamingId(null);
  };

  const handleRenameKey = (event: React.KeyboardEvent, convId: string) => {
    if (event.key === 'Enter') void commitRename(convId);
    if (event.key === 'Escape') setRenamingId(null);
  };

  const filteredConversations = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();

    return conversations.filter((conversation) => {
      const titleMatches = !query || conversation.title.toLowerCase().includes(query);
      if (!titleMatches) return false;

      const meta = conversationMeta[conversation.id];
      if (filter === 'all') return true;
      if (filter === 'pinned') return Boolean(meta?.pinned);
      return (meta?.category || 'general') === filter;
    });
  }, [conversationMeta, conversations, filter, searchQuery]);

  const pinnedConversations = useMemo(
    () => filteredConversations.filter((conversation) => conversationMeta[conversation.id]?.pinned),
    [conversationMeta, filteredConversations]
  );

  const regularConversations = useMemo(
    () => filteredConversations.filter((conversation) => !conversationMeta[conversation.id]?.pinned),
    [conversationMeta, filteredConversations]
  );

  const grouped = groupByDate(regularConversations);

  return (
    <>
      {isOpen && (
        <div
          onClick={onClose}
          aria-hidden="true"
          style={{ position: 'fixed', inset: 0, background: 'rgba(2,6,23,0.34)', backdropFilter: 'blur(2px)', zIndex: 299 }}
        />
      )}

      <aside
        aria-hidden={!isOpen}
        aria-label="Conversation sidebar"
        data-testid="conversation-sidebar"
        style={{
          position: 'fixed',
          top: 0,
          left: 0,
          bottom: 0,
          width: 'min(88vw, 360px)',
          background: 'var(--chat-bg)',
          borderRight: '1px solid var(--chat-border)',
          transform: isOpen ? 'translateX(0)' : 'translateX(-100%)',
          transition: 'transform 0.24s cubic-bezier(0.4,0,0.2,1)',
          zIndex: 300,
          display: 'flex',
          flexDirection: 'column',
          boxShadow: isOpen ? 'var(--chat-shadow-lg)' : 'none',
          paddingBottom: 'env(safe-area-inset-bottom, 0px)',
        }}
      >
        <div
          style={{
            padding: '18px 16px 14px',
            borderBottom: '1px solid var(--chat-border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
          }}
        >
          <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
              <div
                style={{
                  width: 28,
                  height: 28,
                  borderRadius: 9,
                  background: 'var(--chat-surface)',
                  border: '1px solid var(--chat-border)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'var(--ion-color-primary)',
                }}
              >
                <IonIcon icon={chatbubbleEllipsesOutline} style={{ fontSize: 14 }} />
              </div>
              <span style={{ fontSize: 15, fontWeight: 800, color: 'var(--chat-text)', letterSpacing: '-0.03em' }}>
                Conversations
              </span>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close sidebar"
            style={iconButtonStyle}
          >
            <IonIcon icon={closeOutline} />
          </button>
        </div>

        <div style={{ padding: '12px 12px 8px' }}>
          {deleteError && (
            <div
              role="alert"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 8,
                marginBottom: 10,
                padding: '10px 12px',
                borderRadius: 14,
                border: '1px solid rgba(220,38,38,0.16)',
                background: 'rgba(220,38,38,0.06)',
                color: 'var(--ion-color-danger)',
                fontSize: 12.5,
                lineHeight: 1.5,
              }}
            >
              <span>{deleteError}</span>
              {onDismissDeleteError && (
                <button
                  type="button"
                  onClick={onDismissDeleteError}
                  aria-label="Dismiss delete error"
                  style={{
                    border: 'none',
                    background: 'transparent',
                    color: 'inherit',
                    cursor: 'pointer',
                    padding: 0,
                    display: 'flex',
                    alignItems: 'center',
                  }}
                >
                  <IonIcon icon={closeOutline} />
                </button>
              )}
            </div>
          )}
          <div style={searchBoxStyle}>
            <IonIcon icon={searchOutline} style={{ color: 'var(--chat-muted-2)', fontSize: 15 }} />
            <input
              ref={searchInputRef}
              type="text"
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="Search by title..."
              style={searchInputStyle}
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => setSearchQuery('')}
                aria-label="Clear conversation search"
                style={clearButtonStyle}
              >
                <IonIcon icon={closeOutline} />
              </button>
            )}
          </div>
        </div>

        <div style={{ padding: '0 12px 8px', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {FILTERS.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setFilter(item.id)}
              style={{
                padding: '6px 10px',
                borderRadius: 999,
                border: '1px solid var(--chat-border)',
                background: filter === item.id ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
                color: filter === item.id ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                cursor: 'pointer',
                fontSize: 11,
                fontWeight: 700,
              }}
            >
              {item.label}
            </button>
          ))}
        </div>

        <div style={{ padding: '8px 12px 10px' }}>
          <button
            type="button"
            onClick={onNew}
            data-testid="new-conversation-button"
            style={{
              width: '100%',
              padding: '11px 14px',
              borderRadius: 14,
              border: '1px dashed var(--chat-border-2)',
              background: 'var(--chat-surface)',
              color: 'var(--ion-color-primary)',
              fontSize: 12.5,
              fontWeight: 700,
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
            }}
          >
            <IonIcon icon={addOutline} />
            New conversation
          </button>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px 18px' }}>
          {filteredConversations.length === 0 && (
            <EmptyStateCard
              searchQuery={searchQuery}
              onNew={onNew}
            />
          )}

          {pinnedConversations.length > 0 && (
            <div>
              <SectionLabel label="Pinned" count={pinnedConversations.length} />
              {pinnedConversations.map((conversation) => (
                <ConversationRow
                  key={conversation.id}
                  conversation={conversation}
                  category={conversationMeta[conversation.id]?.category || 'general'}
                  isPinned={Boolean(conversationMeta[conversation.id]?.pinned)}
                  isActive={conversation.id === activeId}
                  isHovered={conversation.id === hoveredId}
                  isRenaming={conversation.id === renamingId}
                  renameValue={renameValue}
                  renameInputRef={renameInputRef}
                  onHover={setHoveredId}
                  onSelect={onSelect}
                  onStartRename={startRename}
                  onRenameValue={setRenameValue}
                  onRenameKey={handleRenameKey}
                  onRenameBlur={commitRename}
                  onDelete={onDelete}
                  onTogglePin={onTogglePin}
                  onExport={onExport}
                  deletingId={deletingId}
                  setDeletingId={setDeletingId}
                />
              ))}
            </div>
          )}

          {grouped.map(({ label, items }) => (
            <div key={label}>
              <SectionLabel label={label} count={items.length} />
              {items.map((conversation) => (
                <ConversationRow
                  key={conversation.id}
                  conversation={conversation}
                  category={conversationMeta[conversation.id]?.category || 'general'}
                  isPinned={Boolean(conversationMeta[conversation.id]?.pinned)}
                  isActive={conversation.id === activeId}
                  isHovered={conversation.id === hoveredId}
                  isRenaming={conversation.id === renamingId}
                  renameValue={renameValue}
                  renameInputRef={renameInputRef}
                  onHover={setHoveredId}
                  onSelect={onSelect}
                  onStartRename={startRename}
                  onRenameValue={setRenameValue}
                  onRenameKey={handleRenameKey}
                  onRenameBlur={commitRename}
                  onDelete={onDelete}
                  onTogglePin={onTogglePin}
                  onExport={onExport}
                  deletingId={deletingId}
                  setDeletingId={setDeletingId}
                />
              ))}
            </div>
          ))}
        </div>

        {showAdminPanelLink && onOpenAdminPanel && (
          <div
            style={{
              borderTop: '1px solid var(--chat-border)',
              padding: '12px',
            }}
          >
            <button
              type="button"
              onClick={() => {
                onClose();
                onOpenAdminPanel();
              }}
              data-testid="admin-panel-button"
              style={{
                width: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 10,
                padding: '11px 12px',
                borderRadius: 14,
                border: '1px solid var(--chat-border)',
                background: 'var(--chat-surface)',
                color: 'var(--chat-text)',
                cursor: 'pointer',
              }}
            >
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 8,
                  minWidth: 0,
                }}
              >
                <IonIcon icon={settingsOutline} style={{ fontSize: 15, color: 'var(--ion-color-primary)' }} />
                <span style={{ fontSize: 12.5, fontWeight: 700 }}>
                  Admin Panel
                </span>
              </span>
              <span
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  color: 'var(--chat-muted)',
                  whiteSpace: 'nowrap',
                }}
              >
                Manage
              </span>
            </button>
          </div>
        )}
      </aside>
    </>
  );
};

function SectionLabel({ label, count }: { label: string; count: number }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 8,
        fontSize: 11,
        fontWeight: 800,
        color: 'var(--chat-muted-2)',
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        padding: '14px 10px 6px',
      }}
    >
      <span>{label}</span>
      <span>{count}</span>
    </div>
  );
}

function EmptyStateCard({ searchQuery, onNew }: { searchQuery: string; onNew: () => void }) {
  return (
    <div
      style={{
        textAlign: 'left',
        padding: '16px 12px',
        color: 'var(--chat-muted)',
        borderRadius: 18,
        background: 'var(--chat-surface)',
        border: '1px solid var(--chat-border)',
        margin: '0 4px',
      }}
    >
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--chat-text)', marginBottom: 6 }}>
        {searchQuery ? 'No matching conversations' : 'No saved conversations yet'}
      </div>
      <div style={{ fontSize: 12.5, lineHeight: 1.6 }}>
        {searchQuery ? 'Try another title.' : 'Your conversations will appear here.'}
      </div>
      {!searchQuery && (
        <button
          type="button"
          onClick={onNew}
          style={{
            marginTop: 12,
            padding: '9px 12px',
            borderRadius: 12,
            border: '1px solid var(--chat-border)',
            background: 'var(--chat-bg)',
            color: 'var(--ion-color-primary)',
            fontSize: 12,
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          Start a new thread
        </button>
      )}
    </div>
  );
}

interface RowProps {
  conversation: Conversation;
  category: UiConversationCategory;
  isPinned: boolean;
  isActive: boolean;
  isHovered: boolean;
  isRenaming: boolean;
  renameValue: string;
  renameInputRef: React.RefObject<HTMLInputElement | null>;
  onHover: (id: string | null) => void;
  onSelect: (convId: string) => void;
  onStartRename: (conversation: Conversation, event: React.MouseEvent) => void;
  onRenameValue: (value: string) => void;
  onRenameKey: (event: React.KeyboardEvent, convId: string) => void;
  onRenameBlur: (convId: string) => Promise<void>;
  onDelete: (convId: string) => Promise<void> | void;
  onTogglePin: (convId: string) => void;
  onExport: (convId: string, format: ExportFormat) => void;
  deletingId: string | null;
  setDeletingId: React.Dispatch<React.SetStateAction<string | null>>;
}

function ConversationRow({
  conversation,
  category,
  isPinned,
  isActive,
  isHovered,
  isRenaming,
  renameValue,
  renameInputRef,
  onHover,
  onSelect,
  onStartRename,
  onRenameValue,
  onRenameKey,
  onRenameBlur,
  onDelete,
  onTogglePin,
  onExport,
  deletingId,
  setDeletingId,
}: RowProps) {
  const isDeleting = deletingId === conversation.id;
  const showActions = !isRenaming && (isHovered || isActive || isDeleting);

  return (
    <div
      data-testid="conversation-item"
      data-active={isActive ? 'true' : 'false'}
      data-conversation-id={conversation.id}
      onMouseEnter={() => onHover(conversation.id)}
      onMouseLeave={() => onHover(null)}
      onClick={() => !isRenaming && onSelect(conversation.id)}
      style={{
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '10px 10px 10px 12px',
        borderRadius: 14,
        background: isActive ? 'var(--chat-active)' : isHovered ? 'var(--chat-hover)' : 'transparent',
        border: `1px solid ${isActive ? 'rgba(93,125,148,0.24)' : 'transparent'}`,
        cursor: isRenaming ? 'default' : 'pointer',
        marginBottom: 4,
        transition: 'background 0.12s ease, border-color 0.12s ease',
      }}
    >
      <div
        style={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          background: isActive ? 'var(--ion-color-primary)' : 'var(--chat-border-2)',
          flexShrink: 0,
          boxShadow: isActive ? '0 0 0 4px rgba(93,125,148,0.08)' : 'none',
        }}
      />

      <div style={{ flex: 1, minWidth: 0 }}>
        {isRenaming ? (
          <input
            ref={renameInputRef}
            value={renameValue}
            onChange={(event) => onRenameValue(event.target.value)}
            onKeyDown={(event) => onRenameKey(event, conversation.id)}
            onBlur={() => void onRenameBlur(conversation.id)}
            onClick={(event) => event.stopPropagation()}
            style={renameInputStyle}
          />
        ) : (
          <>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                minWidth: 0,
                marginBottom: 3,
              }}
            >
              <div
                style={{
                  fontSize: 13,
                  fontWeight: isActive ? 700 : 600,
                  color: 'var(--chat-text)',
                  display: '-webkit-box',
                  WebkitLineClamp: 2,
                  WebkitBoxOrient: 'vertical',
                  overflow: 'hidden',
                  lineHeight: 1.35,
                }}
                title={conversation.title}
              >
                {conversation.title}
              </div>
              {isPinned && (
                <IonIcon icon={star} style={{ color: 'var(--ion-color-warning)', fontSize: 12, flexShrink: 0 }} />
              )}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11.5, color: 'var(--chat-muted-2)' }}>
                {conversation.message_count} message{conversation.message_count !== 1 ? 's' : ''}
              </span>
              {category !== 'general' && (
                <span
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 4,
                    fontSize: 10.5,
                    color: category === 'security' ? 'var(--ion-color-primary)' : 'var(--chat-muted-2)',
                    textTransform: 'capitalize',
                  }}
                >
                  <IonIcon icon={CATEGORY_ICONS[category]} style={{ fontSize: 11 }} />
                  {category}
                </span>
              )}
            </div>
          </>
        )}
      </div>

      {showActions && (
        <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
          <ActionBtn
            title={isPinned ? 'Unpin conversation' : 'Pin conversation'}
            disabled={isDeleting}
            onClick={(event) => {
              event.stopPropagation();
              onTogglePin(conversation.id);
            }}
          >
            <IonIcon icon={isPinned ? star : starOutline} />
          </ActionBtn>
          <ActionBtn disabled={isDeleting} title="Export markdown" onClick={(event) => { event.stopPropagation(); onExport(conversation.id, 'markdown'); }}>
            <span style={{ fontSize: 9, fontWeight: 800 }}>MD</span>
          </ActionBtn>
          <ActionBtn disabled={isDeleting} title="Export text" onClick={(event) => { event.stopPropagation(); onExport(conversation.id, 'text'); }}>
            <IonIcon icon={downloadOutline} />
          </ActionBtn>
          <ActionBtn disabled={isDeleting} title="Rename conversation" onClick={(event) => onStartRename(conversation, event)}>
            <IonIcon icon={createOutline} />
          </ActionBtn>
          <ActionBtn
            title={isDeleting ? 'Deleting conversation' : 'Delete conversation'}
            danger
            disabled={isDeleting}
            onClick={(event) => {
              event.stopPropagation();
              setDeletingId(conversation.id);
              Promise.resolve(onDelete(conversation.id)).finally(() => {
                setDeletingId((current) => current === conversation.id ? null : current);
              });
            }}
          >
            <IonIcon icon={trashOutline} />
          </ActionBtn>
        </div>
      )}
    </div>
  );
}

const ActionBtn: React.FC<{
  children: React.ReactNode;
  title: string;
  danger?: boolean;
  disabled?: boolean;
  onClick: (e: React.MouseEvent) => void;
}> = ({ children, title, danger, disabled, onClick }) => (
  <button
    type="button"
    title={title}
    aria-label={title}
    disabled={disabled}
    onMouseDown={(event) => event.stopPropagation()}
    onClick={onClick}
    style={{
      width: 28,
      height: 28,
      borderRadius: 9,
      border: '1px solid var(--chat-border)',
      background: 'var(--chat-surface)',
      cursor: disabled ? 'not-allowed' : 'pointer',
      fontSize: 13,
      color: disabled ? 'var(--chat-muted-2)' : danger ? 'var(--ion-color-danger)' : 'var(--chat-muted)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      transition: 'background 0.12s ease, border-color 0.12s ease',
      opacity: disabled ? 0.6 : 1,
    }}
  >
    {children}
  </button>
);

const iconButtonStyle: React.CSSProperties = {
  width: 30,
  height: 30,
  borderRadius: 9,
  border: '1px solid var(--chat-border)',
  background: 'var(--chat-surface)',
  cursor: 'pointer',
  color: 'var(--chat-muted)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
};

const searchBoxStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  background: 'var(--chat-surface)',
  borderRadius: 14,
  border: '1px solid var(--chat-border)',
  padding: '10px 12px',
};

const searchInputStyle: React.CSSProperties = {
  flex: 1,
  border: 'none',
  outline: 'none',
  background: 'transparent',
  fontSize: 13,
  color: 'var(--chat-text)',
  fontFamily: 'inherit',
};

const clearButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  cursor: 'pointer',
  color: 'var(--chat-muted-2)',
  padding: 0,
  display: 'flex',
  alignItems: 'center',
};

const renameInputStyle: React.CSSProperties = {
  width: '100%',
  fontSize: 13,
  fontWeight: 600,
  border: '1.5px solid var(--ion-color-primary)',
  borderRadius: 10,
  padding: '8px 10px',
  outline: 'none',
  color: 'var(--chat-text)',
  background: 'var(--chat-surface)',
  boxSizing: 'border-box',
};

function groupByDate(conversations: Conversation[]): { label: string; items: Conversation[] }[] {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today.getTime() - 86400000);
  const week = new Date(today.getTime() - 7 * 86400000);

  const buckets: Array<{ label: string; items: Conversation[] }> = [
    { label: 'Today', items: [] },
    { label: 'Yesterday', items: [] },
    { label: 'Last 7 Days', items: [] },
    { label: 'Older', items: [] },
  ];

  const bucketMap = Object.fromEntries(
    buckets.map((bucket) => [bucket.label, bucket.items])
  ) as Record<string, Conversation[]>;

  for (const conversation of conversations) {
    const updated = new Date(conversation.updated_at);
    const day = new Date(updated.getFullYear(), updated.getMonth(), updated.getDate());
    if (day >= today) bucketMap.Today.push(conversation);
    else if (day >= yesterday) bucketMap.Yesterday.push(conversation);
    else if (day >= week) bucketMap['Last 7 Days'].push(conversation);
    else bucketMap.Older.push(conversation);
  }

  return buckets
    .map(({ label, items }) => ({
      label,
      items: [...items].sort(
        (left, right) =>
          new Date(right.updated_at).getTime() - new Date(left.updated_at).getTime()
      ),
    }))
    .filter(({ items }) => items.length > 0);
}
