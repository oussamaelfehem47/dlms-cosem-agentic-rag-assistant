import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryHistory } from 'history';
import { Router } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminPage } from './AdminPage';
import {
  activateAdminUser,
  deactivateAdminUser,
  getActuatorHealth,
  getActuatorInfo,
  getAdminUsers,
  getDislikedFeedback,
  getMcpHealth,
  getReflectionStats,
  hardDeleteAdminUser,
  registerUser,
  updateAdminUserRole,
} from '../adminApi';
import { getUserActiveConversationStorageKey } from '../utils/userScopedStorage';

vi.mock('../adminApi', () => ({
  activateAdminUser: vi.fn(),
  deactivateAdminUser: vi.fn(),
  getActuatorHealth: vi.fn(),
  getActuatorInfo: vi.fn(),
  getAdminUsers: vi.fn(),
  getDislikedFeedback: vi.fn(),
  getMcpHealth: vi.fn(),
  getReflectionStats: vi.fn(),
  hardDeleteAdminUser: vi.fn(),
  registerUser: vi.fn(),
  updateAdminUserRole: vi.fn(),
}));

const mockedActivateAdminUser = vi.mocked(activateAdminUser);
const mockedDeactivateAdminUser = vi.mocked(deactivateAdminUser);
const mockedGetActuatorHealth = vi.mocked(getActuatorHealth);
const mockedGetActuatorInfo = vi.mocked(getActuatorInfo);
const mockedGetAdminUsers = vi.mocked(getAdminUsers);
const mockedGetDislikedFeedback = vi.mocked(getDislikedFeedback);
const mockedGetMcpHealth = vi.mocked(getMcpHealth);
const mockedGetReflectionStats = vi.mocked(getReflectionStats);
const mockedHardDeleteAdminUser = vi.mocked(hardDeleteAdminUser);
const mockedRegisterUser = vi.mocked(registerUser);
const mockedUpdateAdminUserRole = vi.mocked(updateAdminUserRole);

const adminUser = {
  userId: 'admin-1',
  username: 'admin-user',
  email: 'admin@example.com',
  role: 'ADMIN',
  active: true,
  createdAt: '2026-06-03T09:00:00.000Z',
};

const engineerUser = {
  userId: 'eng-1',
  username: 'engineer-user',
  email: 'engineer@example.com',
  role: 'ENGINEER',
  active: true,
  createdAt: '2026-06-03T08:00:00.000Z',
};

function renderAdminPage(section: string) {
  const history = createMemoryHistory({ initialEntries: [`/admin/${section}`] });

  render(
    <Router history={history}>
      <AdminPage
        token="admin-token"
        userId={adminUser.userId}
        username={adminUser.username}
        role={adminUser.role}
        section={section}
      />
    </Router>,
  );

  return { history };
}

function findRowByCellText(text: string): HTMLTableRowElement {
  const cell = screen.getAllByText(text).find((element) => element.closest('tr'));
  expect(cell).toBeDefined();
  return cell?.closest('tr') as HTMLTableRowElement;
}

describe('AdminPage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders overview metrics, sorts intent distribution, and refreshes health data', async () => {
    mockedGetReflectionStats.mockResolvedValue({
      totalExecutions: 338,
      feedbackDatasetSize: 4,
      dislikedResponseCount: 9,
      mcpFailureRate: 0.12,
      intentDistribution: {
        FRAME_DECODE: 220,
        DOCUMENTATION: 118,
      },
    });
    mockedGetActuatorHealth.mockResolvedValue({
      status: 'UP',
      components: {
        db: { status: 'UP' },
        diskSpace: { status: 'UP' },
      },
    });

    const user = userEvent.setup();
    renderAdminPage('overview');

    expect(await screen.findByText('Total Executions')).toBeInTheDocument();
    expect(screen.getByText('HEALTHY')).toBeInTheDocument();
    expect(screen.getByText(/Last updated/)).toBeInTheDocument();
    expect(screen.getByText('db - UP')).toBeInTheDocument();

    const countHeader = screen.getByRole('columnheader', { name: 'Count' });
    const table = countHeader.closest('table');
    expect(table).not.toBeNull();
    const rows = within(table as HTMLTableElement).getAllByRole('row');
    expect(within(rows[1]).getByText('FRAME_DECODE')).toBeInTheDocument();
    expect(within(rows[2]).getByText('DOCUMENTATION')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Refresh$/i }));

    await waitFor(() => {
      expect(mockedGetReflectionStats).toHaveBeenCalledTimes(2);
      expect(mockedGetActuatorHealth).toHaveBeenCalledTimes(2);
    });
  });

  it('disables self-protecting actions while leaving real admin controls available for other users', async () => {
    mockedGetAdminUsers.mockResolvedValue([adminUser, engineerUser]);

    renderAdminPage('users');

    await screen.findByText(adminUser.username);

    const selfRow = findRowByCellText(adminUser.username);
    expect(
      within(selfRow).getByRole('button', { name: 'Deactivate' }),
    ).toBeDisabled();
    expect(
      within(selfRow).getByRole('button', { name: 'Change Role' }),
    ).toBeDisabled();
    expect(
      within(selfRow).getByRole('button', { name: 'Delete' }),
    ).toBeDisabled();

    const engineerRow = findRowByCellText(engineerUser.username);
    expect(
      within(engineerRow).getByRole('button', { name: 'Change Role' }),
    ).toBeEnabled();
    expect(
      within(engineerRow).getByRole('button', { name: 'Delete' }),
    ).toBeEnabled();
  });

  it('deactivates a user and shows the inactive status returned by the backend', async () => {
    const inactiveEngineer = {
      ...engineerUser,
      active: false,
    };
    mockedGetAdminUsers
      .mockResolvedValueOnce([adminUser, engineerUser])
      .mockResolvedValueOnce([adminUser, inactiveEngineer]);
    mockedDeactivateAdminUser.mockResolvedValue(undefined);

    const user = userEvent.setup();
    renderAdminPage('users');

    await screen.findByText(engineerUser.username);

    const engineerRow = findRowByCellText(engineerUser.username);

    await user.click(
      within(engineerRow).getByRole('button', { name: 'Deactivate' }),
    );

    const dialog = await screen.findByRole('dialog', { name: /Deactivate user confirmation/i });
    expect(within(dialog).getByText('Deactivate user?')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    await waitFor(() => {
      expect(mockedDeactivateAdminUser).toHaveBeenCalledWith('admin-token', engineerUser.userId);
    });

    await screen.findByText(engineerUser.username);
    const row = findRowByCellText(engineerUser.username);
    expect(within(row).getByText('Inactive')).toBeInTheDocument();
    expect(
      within(row).getByRole('button', { name: 'Activate' }),
    ).toBeEnabled();
  });

  it('activates an inactive user', async () => {
    const inactiveEngineer = {
      ...engineerUser,
      active: false,
    };
    mockedGetAdminUsers
      .mockResolvedValueOnce([adminUser, inactiveEngineer])
      .mockResolvedValueOnce([adminUser, engineerUser]);
    mockedActivateAdminUser.mockResolvedValue(engineerUser);

    const user = userEvent.setup();
    renderAdminPage('users');

    await screen.findByText(inactiveEngineer.username);

    await user.click(
      within(findRowByCellText(inactiveEngineer.username)).getByRole('button', { name: 'Activate' }),
    );

    await waitFor(() => {
      expect(mockedActivateAdminUser).toHaveBeenCalledWith('admin-token', inactiveEngineer.userId);
    });

    const row = findRowByCellText(engineerUser.username);
    expect(within(row).getByText('Active')).toBeInTheDocument();
  });

  it('changes a user role through the role dialog', async () => {
    const promotedEngineer = {
      ...engineerUser,
      role: 'ADMIN',
    };
    mockedGetAdminUsers
      .mockResolvedValueOnce([adminUser, engineerUser])
      .mockResolvedValueOnce([adminUser, promotedEngineer]);
    mockedUpdateAdminUserRole.mockResolvedValue(promotedEngineer);

    const user = userEvent.setup();
    renderAdminPage('users');

    await screen.findByText(engineerUser.username);
    await user.click(
      within(findRowByCellText(engineerUser.username)).getByRole('button', { name: 'Change Role' }),
    );

    const dialog = await screen.findByRole('dialog', { name: /Change role dialog/i });
    await user.selectOptions(within(dialog).getByLabelText('Role'), 'ADMIN');
    await user.click(within(dialog).getByRole('button', { name: 'Save Role' }));

    await waitFor(() => {
      expect(mockedUpdateAdminUserRole).toHaveBeenCalledWith('admin-token', engineerUser.userId, 'ADMIN');
    });

    const row = findRowByCellText(engineerUser.username);
    expect(within(row).getByText('ADMIN')).toBeInTheDocument();
  });

  it('hard deletes a user after confirmation', async () => {
    mockedGetAdminUsers
      .mockResolvedValueOnce([adminUser, engineerUser])
      .mockResolvedValueOnce([adminUser]);
    mockedHardDeleteAdminUser.mockResolvedValue(undefined);

    const user = userEvent.setup();
    renderAdminPage('users');

    await screen.findByText(engineerUser.username);
    await user.click(
      within(findRowByCellText(engineerUser.username)).getByRole('button', { name: 'Delete' }),
    );

    const dialog = await screen.findByRole('dialog', { name: /Delete user confirmation/i });
    expect(within(dialog).getByText('Permanently delete user?')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Delete Permanently' }));

    await waitFor(() => {
      expect(mockedHardDeleteAdminUser).toHaveBeenCalledWith('admin-token', engineerUser.userId);
    });

    await waitFor(() => {
      expect(screen.queryByText(engineerUser.username)).not.toBeInTheDocument();
    });
  });

  it('shows inline registration errors and then creates a user without replacing the current session', async () => {
    const newUser = {
      userId: 'viewer-2',
      username: 'new-viewer',
      email: 'new-viewer@example.com',
      role: 'VIEWER',
      active: true,
      createdAt: '2026-06-03T11:30:00.000Z',
    };

    mockedGetAdminUsers
      .mockResolvedValueOnce([adminUser, engineerUser])
      .mockResolvedValueOnce([adminUser, engineerUser, newUser]);
    mockedRegisterUser
      .mockRejectedValueOnce(new Error('Email already exists'))
      .mockResolvedValueOnce(undefined);

    const user = userEvent.setup();
    renderAdminPage('users');

    await screen.findByText(engineerUser.username);

    await user.click(screen.getByRole('button', { name: /^Register New User$/i }));

    const dialog = await screen.findByRole('dialog', { name: /Register new user dialog/i });

    await user.type(within(dialog).getByLabelText('Username'), newUser.username);
    await user.type(within(dialog).getByLabelText('Email'), newUser.email);
    await user.type(within(dialog).getByLabelText('Password'), 'password123');
    await user.selectOptions(within(dialog).getByLabelText('Role'), 'ENGINEER');

    await user.click(within(dialog).getByRole('button', { name: 'Create User' }));

    expect(await within(dialog).findByText('Email already exists')).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText('Username'));
    await user.type(within(dialog).getByLabelText('Username'), newUser.username);
    await user.clear(within(dialog).getByLabelText('Email'));
    await user.type(within(dialog).getByLabelText('Email'), newUser.email);

    await user.click(within(dialog).getByRole('button', { name: 'Create User' }));

    await waitFor(() => {
      expect(mockedRegisterUser).toHaveBeenLastCalledWith('admin-token', {
        username: newUser.username,
        email: newUser.email,
        password: 'password123',
        role: 'ENGINEER',
      });
    });

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /Register new user dialog/i })).not.toBeInTheDocument();
    });

    expect(await screen.findByText(newUser.username)).toBeInTheDocument();
  });

  it('shows the feedback empty state when there are no disliked responses', async () => {
    mockedGetDislikedFeedback.mockResolvedValue([]);

    renderAdminPage('feedback');

    expect(await screen.findByText('No disliked responses recorded yet.')).toBeInTheDocument();
  });

  it('expands feedback entries and restores the target conversation before returning to chat', async () => {
    const longPrompt = `Prompt ${'A'.repeat(180)}`;
    const longResponse = `Response ${'B'.repeat(180)}`;

    mockedGetDislikedFeedback.mockResolvedValue([
      {
        id: 'feedback-1',
        conversationId: 'conv-55',
        intent: 'FRAME_DECODE',
        inputClass: 'query',
        feedback: 'dislike',
        promptSnapshot: longPrompt,
        responseSnapshot: longResponse,
        modelName: 'qwen2.5:3b',
        createdAt: '2026-06-03T10:00:00.000Z',
      },
    ]);

    const user = userEvent.setup();
    const { history } = renderAdminPage('feedback');

    const promptButton = await screen.findByRole('button', { name: /Prompt:/i });
    expect(promptButton).toHaveTextContent('...');
    expect(promptButton).not.toHaveTextContent(longPrompt);

    await user.click(promptButton);
    expect(promptButton).toHaveTextContent(longPrompt);

    const responseButton = screen.getByRole('button', { name: /Response:/i });
    await user.click(responseButton);
    expect(responseButton).toHaveTextContent(longResponse);

    await user.click(screen.getByRole('button', { name: /Conversation/i }));

    expect(localStorage.getItem(getUserActiveConversationStorageKey(adminUser.userId))).toBe('conv-55');
    expect(history.location.pathname).toBe('/chat');
  });

  it('renders system status, shows the MCP tool list, and copies the audit query', async () => {
    mockedGetActuatorHealth.mockResolvedValue({
      status: 'UP',
      components: {
        db: { status: 'UP' },
        diskSpace: { status: 'UP' },
        livenessState: { status: 'UP' },
      },
    });
    mockedGetMcpHealth.mockResolvedValue({
      reachable: false,
      toolCount: 9,
      tools: [
        'dlms.parse_hdlc',
        'dlms.decode_axdr',
        'dlms.resolve_obis',
        'dlms.assemble_gbt',
        'siconia.decode_alarm',
        'siconia.parse_xml',
        'siconia.classify_log',
        'confluence.search',
        'confluence.get_page',
      ],
      error: 'MCP proxy unavailable',
    });
    mockedGetActuatorInfo.mockResolvedValue({});

    const user = userEvent.setup();
    const copySpy = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: copySpy,
      },
    });
    renderAdminPage('system');

    expect(await screen.findByText('db - UP')).toBeInTheDocument();
    expect(screen.getByText('unreachable')).toBeInTheDocument();
    expect(screen.getByText('Tool count: 9')).toBeInTheDocument();
    expect(screen.getByText('confluence.search')).toBeInTheDocument();
    expect(screen.getByText('No actuator info exposed.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Copy$/i }));

    expect(copySpy).toHaveBeenCalledWith(
      'docker exec dlms-postgres psql -U postgres -d dlms_assistant -c "SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT 10;"',
    );
  });
});
