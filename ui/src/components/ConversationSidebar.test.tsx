import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConversationSidebar } from './ConversationSidebar';
import { LocalConversationMetaMap } from '../chat/chatFeatureUtils';
import { Conversation } from '../hooks/useConversations';

const conversations: Conversation[] = [
  {
    id: 'conv-1',
    title: 'Troubleshoot meter',
    created_at: '2026-06-01T08:00:00.000Z',
    updated_at: '2026-06-02T08:00:00.000Z',
    message_count: 3,
  },
];

const conversationMeta: LocalConversationMetaMap = {
  'conv-1': {
    pinned: false,
    category: 'incident',
    manualTitle: false,
    autoRetitled: false,
  },
};

function renderSidebar(overrides?: Partial<React.ComponentProps<typeof ConversationSidebar>>) {
  const onClose = vi.fn();
  const onOpenAdminPanel = vi.fn();

  render(
    <ConversationSidebar
      conversations={conversations}
      activeId="conv-1"
      conversationMeta={conversationMeta}
      onSelect={vi.fn()}
      onNew={vi.fn()}
      onDelete={vi.fn()}
      onRename={vi.fn().mockResolvedValue(true)}
      onTogglePin={vi.fn()}
      onExport={vi.fn()}
      isOpen
      onClose={onClose}
      showAdminPanelLink={false}
      onOpenAdminPanel={onOpenAdminPanel}
      {...overrides}
    />,
  );

  return { onClose, onOpenAdminPanel };
}

describe('ConversationSidebar admin entry', () => {
  it('shows the Admin Panel action for admins and navigates through the callback', async () => {
    const user = userEvent.setup();
    const { onClose, onOpenAdminPanel } = renderSidebar({
      showAdminPanelLink: true,
    });

    const adminButton = screen.getByRole('button', { name: /Admin Panel/i });
    expect(adminButton).toBeInTheDocument();

    await user.click(adminButton);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onOpenAdminPanel).toHaveBeenCalledTimes(1);
  });

  it('hides the Admin Panel action for non-admin users', () => {
    renderSidebar();

    expect(screen.queryByRole('button', { name: /Admin Panel/i })).not.toBeInTheDocument();
  });
});
