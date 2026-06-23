import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  IonIcon,
  IonSpinner,
  IonToast,
} from '@ionic/react';
import {
  alertCircleOutline,
  checkmarkCircleOutline,
  chevronDownOutline,
  chevronUpOutline,
  closeOutline,
  copyOutline,
  createOutline,
  eyeOffOutline,
  eyeOutline,
  openOutline,
  personAddOutline,
  refreshOutline,
  searchOutline,
  trashOutline,
} from 'ionicons/icons';
import { Redirect, useHistory } from 'react-router-dom';
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
import { CHAT_BADGE } from '../chat/chatConfig';
import { AdminLayout, AdminSection } from '../layouts/AdminLayout';
import {
  ActuatorInfoResponse,
  AdminFeedback,
  AdminUser,
  HealthResponse,
  InputClass,
  McpHealthResponse,
  ReflectionStatsResponse,
  RegisterUserRequest,
} from '../types';
import { getUserActiveConversationStorageKey } from '../utils/userScopedStorage';

const VALID_SECTIONS: AdminSection[] = ['overview', 'users', 'feedback', 'system'];
const INPUT_CLASS_VALUES: InputClass[] = [
  'hex_frame',
  'xml_trace',
  'alarm_code',
  'log_block',
  'query',
  'unknown',
];
const USER_ROLE_OPTIONS = ['VIEWER', 'ENGINEER', 'ADMIN'] as const;
type AdminRoleOption = (typeof USER_ROLE_OPTIONS)[number];
const AUDIT_LOG_COMMAND = "docker exec dlms-postgres psql -U postgres -d dlms_assistant -c \"SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT 10;\"";

const panelStyle: React.CSSProperties = {
  border: '1px solid var(--chat-border)',
  borderRadius: 18,
  background: 'var(--chat-surface)',
  boxShadow: 'var(--chat-shadow)',
};

const sectionCardStyle: React.CSSProperties = {
  ...panelStyle,
  padding: 18,
};

const secondaryButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  padding: '9px 12px',
  borderRadius: 12,
  borderWidth: 1,
  borderStyle: 'solid',
  borderColor: 'var(--chat-border)',
  background: 'var(--chat-surface)',
  color: 'var(--chat-text)',
  cursor: 'pointer',
  fontSize: 12.5,
  fontWeight: 700,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '11px 12px',
  borderRadius: 12,
  border: '1px solid var(--chat-input-border)',
  background: 'var(--chat-input-bg)',
  color: 'var(--chat-text)',
  fontSize: 13,
  outline: 'none',
  boxSizing: 'border-box',
};

const selectStyle: React.CSSProperties = {
  ...inputStyle,
  appearance: 'none',
};

function normalizeSection(section?: string): AdminSection | null {
  if (!section) return null;
  return VALID_SECTIONS.includes(section as AdminSection)
    ? (section as AdminSection)
    : null;
}

function normalizeRole(role?: string | null): string {
  return (role || '').toUpperCase();
}

function formatRoleLabel(role?: string): string {
  const normalized = normalizeRole(role);
  if (!normalized) return 'Unknown';
  return normalized.charAt(0) + normalized.slice(1).toLowerCase();
}

function formatDateTime(value?: string): string {
  if (!value) return 'Not exposed';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? 'Not exposed' : parsed.toLocaleString();
}

function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

function truncateText(value: string | undefined, limit: number): string {
  if (!value) return 'Not recorded';
  return value.length <= limit ? value : `${value.slice(0, limit).trimEnd()}...`;
}

function formatRelativeTime(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return 'Unknown time';

  const diffMs = parsed.getTime() - Date.now();
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 1000 * 60 * 60 * 24 * 365],
    ['month', 1000 * 60 * 60 * 24 * 30],
    ['week', 1000 * 60 * 60 * 24 * 7],
    ['day', 1000 * 60 * 60 * 24],
    ['hour', 1000 * 60 * 60],
    ['minute', 1000 * 60],
    ['second', 1000],
  ];

  for (const [unit, size] of units) {
    if (Math.abs(diffMs) >= size || unit === 'second') {
      return formatter.format(Math.round(diffMs / size), unit);
    }
  }

  return 'just now';
}

function badgeStyle(background: string, color: string, border?: string): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    padding: '5px 9px',
    borderRadius: 999,
    background,
    color,
    border: `1px solid ${border || 'transparent'}`,
    fontSize: 11,
    fontWeight: 800,
    lineHeight: 1,
    whiteSpace: 'nowrap',
  };
}

function statusBadgeStyle(status: string): React.CSSProperties {
  return status === 'UP'
    ? badgeStyle('rgba(34,197,94,0.12)', 'var(--ion-color-success)', 'rgba(34,197,94,0.24)')
    : badgeStyle('rgba(220,38,38,0.12)', 'var(--ion-color-danger)', 'rgba(220,38,38,0.24)');
}

function roleBadgeStyle(role: string): React.CSSProperties {
  switch (normalizeRole(role)) {
    case 'ADMIN':
      return badgeStyle('rgba(220,38,38,0.12)', 'var(--ion-color-danger)', 'rgba(220,38,38,0.24)');
    case 'ENGINEER':
      return badgeStyle('rgba(93,125,148,0.12)', 'var(--ion-color-primary)', 'rgba(93,125,148,0.24)');
    case 'VIEWER':
    default:
      return badgeStyle('var(--chat-surface-2)', 'var(--chat-muted)', 'var(--chat-border)');
  }
}

function statusTextStyle(active: boolean): React.CSSProperties {
  return active
    ? badgeStyle('rgba(34,197,94,0.12)', 'var(--ion-color-success)', 'rgba(34,197,94,0.24)')
    : badgeStyle('rgba(220,38,38,0.12)', 'var(--ion-color-danger)', 'rgba(220,38,38,0.24)');
}

function normalizeInputClassValue(value?: string): InputClass {
  const normalized = (value || 'unknown').trim().toLowerCase();
  return INPUT_CLASS_VALUES.includes(normalized as InputClass)
    ? (normalized as InputClass)
    : 'unknown';
}

function inputBadgeStyle(value?: string): React.CSSProperties {
  const normalized = normalizeInputClassValue(value);
  const config = CHAT_BADGE[normalized];

  switch (config.color) {
    case 'primary':
      return badgeStyle('rgba(93,125,148,0.12)', 'var(--ion-color-primary)', 'rgba(93,125,148,0.24)');
    case 'tertiary':
      return badgeStyle('rgba(141,123,103,0.12)', 'var(--ion-color-tertiary)', 'rgba(141,123,103,0.24)');
    case 'danger':
      return badgeStyle('rgba(220,38,38,0.12)', 'var(--ion-color-danger)', 'rgba(220,38,38,0.24)');
    case 'warning':
      return badgeStyle('rgba(217,119,6,0.12)', 'var(--ion-color-warning)', 'rgba(217,119,6,0.24)');
    case 'success':
      return badgeStyle('rgba(34,197,94,0.12)', 'var(--ion-color-success)', 'rgba(34,197,94,0.24)');
    case 'medium':
    default:
      return badgeStyle('var(--chat-surface-2)', 'var(--chat-muted)', 'var(--chat-border)');
  }
}

function intentBadgeStyle(intent?: string): React.CSSProperties {
  switch ((intent || '').toUpperCase()) {
    case 'FRAME_DECODE':
    case 'APDU_ANALYSIS':
      return badgeStyle('rgba(93,125,148,0.12)', 'var(--ion-color-primary)', 'rgba(93,125,148,0.24)');
    case 'SICONIA_TROUBLESHOOT':
      return badgeStyle('rgba(217,119,6,0.12)', 'var(--ion-color-warning)', 'rgba(217,119,6,0.24)');
    case 'SECURITY_EXPLAIN':
      return badgeStyle('rgba(220,38,38,0.12)', 'var(--ion-color-danger)', 'rgba(220,38,38,0.24)');
    case 'DOCUMENTATION':
      return badgeStyle('rgba(79,134,133,0.12)', 'var(--ion-color-secondary)', 'rgba(79,134,133,0.24)');
    case 'OBIS_LOOKUP':
      return badgeStyle('rgba(34,197,94,0.12)', 'var(--ion-color-success)', 'rgba(34,197,94,0.24)');
    default:
      return badgeStyle('var(--chat-surface-2)', 'var(--chat-muted)', 'var(--chat-border)');
  }
}

function sectionTitleStyle(): React.CSSProperties {
  return {
    fontSize: 20,
    fontWeight: 800,
    color: 'var(--chat-text)',
    letterSpacing: '-0.03em',
  };
}

function renderInfoValue(value: unknown): string {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

interface AdminPageProps {
  token: string;
  userId: string;
  username?: string;
  role?: string;
  section?: string;
}

export const AdminPage: React.FC<AdminPageProps> = ({
  token,
  userId,
  username,
  role,
  section,
}) => {
  const history = useHistory();
  const normalizedSection = normalizeSection(section);
  const [toast, setToast] = useState<{ message: string; color?: string } | null>(null);

  const showToast = useCallback((message: string, color?: string) => {
    setToast({ message, color });
  }, []);

  if (!normalizedSection) {
    return <Redirect to="/admin/overview" />;
  }

  return (
    <>
      <AdminLayout
        activeSection={normalizedSection}
        username={username}
        role={role}
        onNavigate={(nextSection) => history.push(`/admin/${nextSection}`)}
        onBackToChat={() => history.push('/chat')}
      >
        {normalizedSection === 'overview' && (
          <OverviewSection token={token} />
        )}
        {normalizedSection === 'users' && (
          <UsersSection
            token={token}
            currentUserId={userId}
            onToast={showToast}
          />
        )}
        {normalizedSection === 'feedback' && (
          <FeedbackSection
            token={token}
            userId={userId}
            onToast={showToast}
          />
        )}
        {normalizedSection === 'system' && (
          <SystemSection
            token={token}
            onToast={showToast}
          />
        )}
      </AdminLayout>

      <IonToast
        isOpen={Boolean(toast)}
        message={toast?.message}
        color={toast?.color}
        duration={2200}
        position="top"
        onDidDismiss={() => setToast(null)}
      />
    </>
  );
};

const SectionHeader: React.FC<{
  title: string;
  description: string;
  onRefresh?: () => void;
  refreshing?: boolean;
  rightMeta?: React.ReactNode;
}> = ({ title, description, onRefresh, refreshing, rightMeta }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'flex-start',
      justifyContent: 'space-between',
      gap: 16,
      marginBottom: 18,
      flexWrap: 'wrap',
    }}
  >
    <div>
      <div style={sectionTitleStyle()}>{title}</div>
      <div
        style={{
          marginTop: 6,
          color: 'var(--chat-muted)',
          fontSize: 13.5,
          lineHeight: 1.6,
        }}
      >
        {description}
      </div>
    </div>

    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        flexWrap: 'wrap',
      }}
    >
      {rightMeta}
      {onRefresh && (
        <button
          type="button"
          onClick={onRefresh}
          style={secondaryButtonStyle}
        >
          <IonIcon icon={refreshOutline} style={{ fontSize: 14 }} />
          {refreshing ? 'Refreshing...' : 'Refresh'}
        </button>
      )}
    </div>
  </div>
);

const OverviewSection: React.FC<{ token: string }> = ({ token }) => {
  const [stats, setStats] = useState<ReflectionStatsResponse | null>(null);
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const load = useCallback(async (background = false) => {
    if (background) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }

    try {
      setError(null);
      const [nextStats, nextHealth] = await Promise.all([
        getReflectionStats(token),
        getActuatorHealth(token),
      ]);
      setStats(nextStats);
      setHealth(nextHealth);
      setLastUpdated(new Date());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load admin overview.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      void load(true);
    }, 30000);

    return () => window.clearInterval(timer);
  }, [load]);

  const intentRows = useMemo(() => {
    const distribution = stats?.intentDistribution || {};
    return Object.entries(distribution)
      .sort((left, right) => right[1] - left[1]);
  }, [stats]);

  const totalExecutions = stats?.totalExecutions || 0;
  const trackedIntents = intentRows.length;
  const healthStatus = health?.status === 'UP' ? 'HEALTHY' : 'DEGRADED';

  return (
    <section>
      <SectionHeader
        title="Overview"
        description="Live reflection metrics and service health for the admin workspace."
        onRefresh={() => void load(true)}
        refreshing={refreshing}
        rightMeta={
          lastUpdated ? (
            <span style={{ color: 'var(--chat-muted)', fontSize: 12.5 }}>
              Last updated {lastUpdated.toLocaleTimeString()}
            </span>
          ) : null
        }
      />

      {loading ? (
        <div style={{ ...sectionCardStyle, display: 'flex', alignItems: 'center', gap: 10 }}>
          <IonSpinner name="crescent" />
          <span style={{ color: 'var(--chat-muted)' }}>Loading overview...</span>
        </div>
      ) : error ? (
        <div
          style={{
            ...sectionCardStyle,
            color: 'var(--ion-color-danger)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <IonIcon icon={alertCircleOutline} style={{ fontSize: 18 }} />
          <span>{error}</span>
        </div>
      ) : (
        <>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
              gap: 14,
              marginBottom: 16,
            }}
          >
            {[
              {
                label: 'Total Executions',
                value: totalExecutions.toLocaleString(),
              },
              {
                label: 'Feedback Size',
                value: (stats?.feedbackDatasetSize || 0).toLocaleString(),
              },
              {
                label: 'Disliked Responses',
                value: (stats?.dislikedResponseCount || 0).toLocaleString(),
              },
              {
                label: stats?.mcpFailureRate !== undefined ? 'MCP Failure Rate' : 'Tracked Intents',
                value: stats?.mcpFailureRate !== undefined
                  ? formatPercent(stats.mcpFailureRate)
                  : trackedIntents.toLocaleString(),
              },
            ].map((card) => (
              <div key={card.label} style={{ ...sectionCardStyle, padding: 16 }}>
                <div
                  style={{
                    fontSize: 30,
                    fontWeight: 800,
                    color: 'var(--chat-primary)',
                    letterSpacing: '-0.04em',
                  }}
                >
                  {card.value}
                </div>
                <div
                  style={{
                    marginTop: 6,
                    color: 'var(--chat-muted)',
                    fontSize: 12.5,
                    fontWeight: 700,
                    textTransform: 'uppercase',
                    letterSpacing: '0.06em',
                  }}
                >
                  {card.label}
                </div>
              </div>
            ))}
          </div>

          <div style={{ ...sectionCardStyle, marginBottom: 16 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                marginBottom: 12,
                flexWrap: 'wrap',
              }}
            >
              <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--chat-text)' }}>
                Intent Distribution
              </div>
              <span style={{ color: 'var(--chat-muted)', fontSize: 12.5 }}>
                Sorted by execution count
              </span>
            </div>

            {intentRows.length === 0 ? (
              <div style={{ color: 'var(--chat-muted)', fontSize: 13 }}>
                No intent executions recorded yet.
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 560 }}>
                  <thead>
                    <tr style={{ color: 'var(--chat-muted)', fontSize: 12 }}>
                      <th style={{ padding: '0 12px 10px', textAlign: 'left' }}>Intent</th>
                      <th style={{ padding: '0 12px 10px', textAlign: 'right' }}>Count</th>
                      <th style={{ padding: '0 12px 10px', textAlign: 'right' }}>% of Total</th>
                      <th style={{ padding: '0 12px 10px', textAlign: 'left' }}>Bar</th>
                    </tr>
                  </thead>
                  <tbody>
                    {intentRows.map(([intent, count], index) => {
                      const ratio = totalExecutions > 0 ? count / totalExecutions : 0;
                      const isTopRow = index === 0;
                      return (
                        <tr
                          key={intent}
                          style={{
                            background: index % 2 === 0 ? 'rgba(255,255,255,0.02)' : 'transparent',
                          }}
                        >
                          <td
                            style={{
                              padding: '12px',
                              borderLeft: `4px solid ${isTopRow ? 'var(--chat-primary)' : 'transparent'}`,
                              fontWeight: isTopRow ? 800 : 700,
                              color: 'var(--chat-text)',
                            }}
                          >
                            {intent}
                          </td>
                          <td style={{ padding: '12px', textAlign: 'right', color: 'var(--chat-text)' }}>
                            {count.toLocaleString()}
                          </td>
                          <td style={{ padding: '12px', textAlign: 'right', color: 'var(--chat-text)' }}>
                            {(ratio * 100).toFixed(1)}%
                          </td>
                          <td style={{ padding: '12px' }}>
                            <div
                              style={{
                                width: '100%',
                                height: 10,
                                borderRadius: 999,
                                background: 'var(--chat-surface-2)',
                                overflow: 'hidden',
                              }}
                            >
                              <div
                                style={{
                                  width: `${Math.max(ratio * 100, 3)}%`,
                                  height: '100%',
                                  borderRadius: 999,
                                  background: isTopRow ? 'var(--chat-primary)' : 'var(--ion-color-secondary)',
                                }}
                              />
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div style={sectionCardStyle}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                marginBottom: 12,
                flexWrap: 'wrap',
              }}
            >
              <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--chat-text)' }}>
                System Health
              </div>
              <span style={statusBadgeStyle(health?.status || 'DOWN')}>
                {healthStatus}
              </span>
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {Object.entries(health?.components || {}).map(([component, componentStatus]) => (
                <span key={component} style={statusBadgeStyle(componentStatus.status)}>
                  {component} - {componentStatus.status}
                </span>
              ))}
              {Object.keys(health?.components || {}).length === 0 && (
                <span style={{ color: 'var(--chat-muted)', fontSize: 13 }}>
                  No component health details exposed.
                </span>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  );
};

const UsersSection: React.FC<{
  token: string;
  currentUserId: string;
  onToast: (message: string, color?: string) => void;
}> = ({ token, currentUserId, onToast }) => {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchValue, setSearchValue] = useState('');
  const [roleFilter, setRoleFilter] = useState<'all' | 'ADMIN' | 'ENGINEER' | 'VIEWER'>('all');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'inactive'>('all');
  const [confirmDeactivateUser, setConfirmDeactivateUser] = useState<AdminUser | null>(null);
  const [confirmDeleteUser, setConfirmDeleteUser] = useState<AdminUser | null>(null);
  const [roleEditorUser, setRoleEditorUser] = useState<AdminUser | null>(null);
  const [roleDraft, setRoleDraft] = useState<AdminRoleOption>('VIEWER');
  const [processingKey, setProcessingKey] = useState<string | null>(null);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [registerError, setRegisterError] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [form, setForm] = useState<RegisterUserRequest>({
    username: '',
    email: '',
    password: '',
    role: 'VIEWER',
  });

  const loadUsers = useCallback(async (background = false) => {
    if (background) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }

    try {
      setError(null);
      const fetchedUsers = await getAdminUsers(token);
      setUsers(
        [...fetchedUsers].sort((left, right) =>
          left.username.localeCompare(right.username),
        ),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load users.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [token]);

  useEffect(() => {
    void loadUsers();
  }, [loadUsers]);

  const openRoleEditor = useCallback((user: AdminUser) => {
    setRoleEditorUser(user);
    setRoleDraft((normalizeRole(user.role) as AdminRoleOption) || 'VIEWER');
  }, []);

  const filteredUsers = useMemo(() => {
    return users.filter((user) => {
      const matchesSearch = !searchValue.trim()
        || user.username.toLowerCase().includes(searchValue.trim().toLowerCase());
      const matchesRole = roleFilter === 'all' || normalizeRole(user.role) === roleFilter;
      const matchesStatus = statusFilter === 'all'
        || (statusFilter === 'active' && user.active)
        || (statusFilter === 'inactive' && !user.active);

      return matchesSearch && matchesRole && matchesStatus;
    });
  }, [roleFilter, searchValue, statusFilter, users]);

  const handleActivate = useCallback(async (user: AdminUser) => {
    const nextProcessingKey = `${user.userId}:activate`;
    setProcessingKey(nextProcessingKey);
    try {
      await activateAdminUser(token, user.userId);
      await loadUsers(true);
      onToast(`User ${user.username} activated`, 'success');
    } catch (err) {
      onToast(err instanceof Error ? err.message : 'Could not activate user.', 'danger');
    } finally {
      setProcessingKey(null);
    }
  }, [loadUsers, onToast, token]);

  const handleDeactivate = useCallback(async (user: AdminUser) => {
    const nextProcessingKey = `${user.userId}:deactivate`;
    setProcessingKey(nextProcessingKey);
    try {
      await deactivateAdminUser(token, user.userId);
      await loadUsers(true);
      onToast(`User ${user.username} deactivated`, 'success');
    } catch (err) {
      onToast(err instanceof Error ? err.message : 'Could not deactivate user.', 'danger');
    } finally {
      setProcessingKey(null);
      setConfirmDeactivateUser(null);
    }
  }, [loadUsers, onToast, token]);

  const handleRoleChange = useCallback(async () => {
    if (!roleEditorUser) {
      return;
    }

    const nextProcessingKey = `${roleEditorUser.userId}:role`;
    setProcessingKey(nextProcessingKey);
    try {
      await updateAdminUserRole(token, roleEditorUser.userId, roleDraft);
      await loadUsers(true);
      onToast(`Role updated for ${roleEditorUser.username}`, 'success');
      setRoleEditorUser(null);
    } catch (err) {
      onToast(err instanceof Error ? err.message : 'Could not update user role.', 'danger');
    } finally {
      setProcessingKey(null);
    }
  }, [loadUsers, onToast, roleDraft, roleEditorUser, token]);

  const handleHardDelete = useCallback(async (user: AdminUser) => {
    const nextProcessingKey = `${user.userId}:delete`;
    setProcessingKey(nextProcessingKey);
    try {
      await hardDeleteAdminUser(token, user.userId);
      await loadUsers(true);
      onToast(`User ${user.username} permanently deleted`, 'success');
    } catch (err) {
      onToast(err instanceof Error ? err.message : 'Could not delete user.', 'danger');
    } finally {
      setProcessingKey(null);
      setConfirmDeleteUser(null);
    }
  }, [loadUsers, onToast, token]);

  const handleRegisterSubmit = useCallback(async (event: React.FormEvent) => {
    event.preventDefault();
    setRegisterError(null);

    if (!form.username.trim() || !form.email.trim() || !form.password.trim()) {
      setRegisterError('Username, email, and password are required.');
      return;
    }

    if (form.password.length < 8) {
      setRegisterError('Password must be at least 8 characters.');
      return;
    }

    setRegistering(true);
    try {
      await registerUser(token, {
        ...form,
        username: form.username.trim(),
        email: form.email.trim(),
        role: normalizeRole(form.role) || 'VIEWER',
      });
      setRegisterOpen(false);
      setForm({ username: '', email: '', password: '', role: 'VIEWER' });
      await loadUsers(true);
      onToast(`User ${form.username.trim()} created`, 'success');
    } catch (err) {
      setRegisterError(err instanceof Error ? err.message : 'Could not create user.');
    } finally {
      setRegistering(false);
    }
  }, [form, loadUsers, onToast, token]);

  return (
    <section>
      <SectionHeader
        title="Users"
        description="Manage account access, role assignments, activation status, and permanent deletion from one place."
        onRefresh={() => void loadUsers(true)}
        refreshing={refreshing}
        rightMeta={
          <button
            type="button"
            onClick={() => setRegisterOpen(true)}
            style={{
              ...secondaryButtonStyle,
              background: 'var(--chat-primary)',
              borderColor: 'var(--chat-primary)',
              color: 'white',
            }}
          >
            <IonIcon icon={personAddOutline} style={{ fontSize: 14 }} />
            Register New User
          </button>
        }
      />

      <div style={{ ...sectionCardStyle, marginBottom: 16 }}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(180px, 1.2fr) minmax(140px, 180px) minmax(140px, 180px)',
            gap: 10,
          }}
        >
          <label style={{ display: 'block' }}>
            <div style={{ color: 'var(--chat-muted)', fontSize: 12, marginBottom: 6 }}>Filter by username</div>
            <div style={{ position: 'relative' }}>
              <input
                value={searchValue}
                onChange={(event) => setSearchValue(event.target.value)}
                placeholder="Search usernames..."
                style={{ ...inputStyle, paddingLeft: 36 }}
              />
              <IonIcon
                icon={searchOutline}
                style={{
                  position: 'absolute',
                  top: 12,
                  left: 12,
                  fontSize: 15,
                  color: 'var(--chat-muted)',
                }}
              />
            </div>
          </label>

          <label style={{ display: 'block' }}>
            <div style={{ color: 'var(--chat-muted)', fontSize: 12, marginBottom: 6 }}>Filter by role</div>
            <select
              value={roleFilter}
              onChange={(event) => setRoleFilter(event.target.value as 'all' | 'ADMIN' | 'ENGINEER' | 'VIEWER')}
              style={selectStyle}
            >
              <option value="all">All roles</option>
              <option value="ADMIN">Admin</option>
              <option value="ENGINEER">Engineer</option>
              <option value="VIEWER">Viewer</option>
            </select>
          </label>

          <label style={{ display: 'block' }}>
            <div style={{ color: 'var(--chat-muted)', fontSize: 12, marginBottom: 6 }}>Filter by status</div>
            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value as 'all' | 'active' | 'inactive')}
              style={selectStyle}
            >
              <option value="all">All statuses</option>
              <option value="active">Active</option>
              <option value="inactive">Inactive</option>
            </select>
          </label>
        </div>

        <div
          style={{
            marginTop: 12,
            color: 'var(--chat-muted)',
            fontSize: 12.5,
            lineHeight: 1.6,
          }}
        >
          `DELETE /api/admin/users/{'{id}'}` deactivates an account, `POST /api/admin/users/{'{id}'}/activate` restores access, `PATCH /api/admin/users/{'{id}'}/role` updates the role, and `DELETE /api/admin/users/{'{id}'}/hard` permanently removes the user and owned artifacts.
        </div>
      </div>

      {loading ? (
        <div style={{ ...sectionCardStyle, display: 'flex', alignItems: 'center', gap: 10 }}>
          <IonSpinner name="crescent" />
          <span style={{ color: 'var(--chat-muted)' }}>Loading users...</span>
        </div>
      ) : error ? (
        <div
          style={{
            ...sectionCardStyle,
            color: 'var(--ion-color-danger)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <IonIcon icon={alertCircleOutline} style={{ fontSize: 18 }} />
          <span>{error}</span>
        </div>
      ) : (
        <div style={{ ...sectionCardStyle, overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 920 }}>
            <thead>
              <tr style={{ color: 'var(--chat-muted)', fontSize: 12 }}>
                <th style={{ padding: '0 12px 12px', textAlign: 'left' }}>Username</th>
                <th style={{ padding: '0 12px 12px', textAlign: 'left' }}>Email</th>
                <th style={{ padding: '0 12px 12px', textAlign: 'left' }}>Role</th>
                <th style={{ padding: '0 12px 12px', textAlign: 'left' }}>Status</th>
                <th style={{ padding: '0 12px 12px', textAlign: 'left' }}>Created</th>
                <th style={{ padding: '0 12px 12px', textAlign: 'left' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredUsers.length === 0 ? (
                <tr>
                  <td colSpan={6} style={{ padding: '16px 12px', color: 'var(--chat-muted)' }}>
                    No users match the current filters.
                  </td>
                </tr>
              ) : (
                filteredUsers.map((user, index) => {
                  const isSelf = user.userId === currentUserId;
                  const rowBusy = processingKey?.startsWith(`${user.userId}:`) || false;
                  return (
                    <tr
                      key={user.userId}
                      style={{
                        background: index % 2 === 0 ? 'rgba(255,255,255,0.02)' : 'transparent',
                        borderTop: index === 0 ? '1px solid var(--chat-border)' : '1px solid rgba(0,0,0,0.03)',
                      }}
                    >
                      <td style={{ padding: '14px 12px', color: 'var(--chat-text)', fontWeight: 700 }}>
                        {user.username}
                      </td>
                      <td style={{ padding: '14px 12px', color: 'var(--chat-text)' }}>{user.email}</td>
                      <td style={{ padding: '14px 12px' }}>
                        <span style={roleBadgeStyle(user.role)}>{normalizeRole(user.role)}</span>
                      </td>
                      <td style={{ padding: '14px 12px' }}>
                        <span style={statusTextStyle(user.active)}>{user.active ? 'Active' : 'Inactive'}</span>
                      </td>
                      <td style={{ padding: '14px 12px', color: 'var(--chat-muted)' }}>
                        {formatDateTime(user.createdAt)}
                      </td>
                      <td style={{ padding: '14px 12px' }}>
                        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                          {!user.active ? (
                            <button
                              type="button"
                              disabled={rowBusy}
                              onClick={() => void handleActivate(user)}
                              title="Restore this user account"
                              style={{
                                ...secondaryButtonStyle,
                                padding: '8px 10px',
                                background: 'rgba(22,163,74,0.12)',
                                borderColor: 'rgba(22,163,74,0.24)',
                                color: 'var(--ion-color-success)',
                              }}
                            >
                              {processingKey === `${user.userId}:activate` ? 'Activating...' : 'Activate'}
                            </button>
                          ) : (
                            <button
                              type="button"
                              disabled={isSelf || rowBusy}
                              onClick={() => setConfirmDeactivateUser(user)}
                              title={isSelf ? 'You cannot deactivate your own account' : 'Deactivate this user'}
                              style={{
                                ...secondaryButtonStyle,
                                padding: '8px 10px',
                                background: 'rgba(217,119,6,0.12)',
                                borderColor: 'rgba(217,119,6,0.24)',
                                color: 'var(--ion-color-warning)',
                                cursor: isSelf ? 'not-allowed' : 'pointer',
                                opacity: isSelf ? 0.7 : 1,
                              }}
                            >
                              {processingKey === `${user.userId}:deactivate` ? 'Working...' : 'Deactivate'}
                            </button>
                          )}

                          <button
                            type="button"
                            disabled={isSelf || rowBusy}
                            onClick={() => openRoleEditor(user)}
                            title={isSelf ? 'You cannot change your own role' : 'Change this user role'}
                            style={{
                              ...secondaryButtonStyle,
                              padding: '8px 10px',
                              background: 'rgba(37,99,235,0.10)',
                              borderColor: 'rgba(37,99,235,0.22)',
                              color: 'var(--ion-color-primary)',
                              cursor: isSelf ? 'not-allowed' : 'pointer',
                              opacity: isSelf ? 0.7 : 1,
                            }}
                          >
                            <IonIcon icon={createOutline} style={{ fontSize: 13 }} />
                            Change Role
                          </button>

                          <button
                            type="button"
                            disabled={isSelf || rowBusy}
                            onClick={() => setConfirmDeleteUser(user)}
                            title={isSelf ? 'You cannot delete your own account' : 'Permanently delete this user'}
                            style={{
                              ...secondaryButtonStyle,
                              padding: '8px 10px',
                              background: 'rgba(220,38,38,0.10)',
                              borderColor: 'rgba(220,38,38,0.20)',
                              color: 'var(--ion-color-danger)',
                              cursor: isSelf ? 'not-allowed' : 'pointer',
                              opacity: isSelf ? 0.7 : 1,
                            }}
                          >
                            <IonIcon icon={trashOutline} style={{ fontSize: 13 }} />
                            Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      )}

      {registerOpen && (
        <div style={overlayStyle}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Register new user dialog"
            style={{ ...sectionCardStyle, width: 'min(100%, 520px)', position: 'relative' }}
          >
            <button
              type="button"
              onClick={() => {
                setRegisterOpen(false);
                setRegisterError(null);
              }}
              aria-label="Close register user modal"
              style={closeButtonStyle}
            >
              <IonIcon icon={closeOutline} />
            </button>

            <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--chat-text)', marginBottom: 6 }}>
              Register New User
            </div>
            <div style={{ color: 'var(--chat-muted)', fontSize: 13.5, marginBottom: 16 }}>
              Create a new user account without replacing your current admin session.
            </div>

            <form onSubmit={handleRegisterSubmit}>
              <div style={{ display: 'grid', gap: 12 }}>
                <label>
                  <div style={fieldLabelStyle}>Username</div>
                  <input
                    value={form.username}
                    onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))}
                    style={inputStyle}
                    required
                  />
                </label>

                <label>
                  <div style={fieldLabelStyle}>Email</div>
                  <input
                    type="email"
                    value={form.email}
                    onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))}
                    style={inputStyle}
                    required
                  />
                </label>

                <label>
                  <div style={fieldLabelStyle}>Password</div>
                  <div style={{ position: 'relative' }}>
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={form.password}
                      onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))}
                      style={{ ...inputStyle, paddingRight: 42 }}
                      required
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((current) => !current)}
                      style={{
                        position: 'absolute',
                        top: 9,
                        right: 8,
                        width: 28,
                        height: 28,
                        border: 'none',
                        background: 'transparent',
                        color: 'var(--chat-muted)',
                        cursor: 'pointer',
                      }}
                    >
                      <IonIcon icon={showPassword ? eyeOffOutline : eyeOutline} />
                    </button>
                  </div>
                </label>

                <label>
                  <div style={fieldLabelStyle}>Role</div>
                  <select
                    value={form.role}
                    onChange={(event) => setForm((current) => ({ ...current, role: event.target.value }))}
                    style={selectStyle}
                  >
                    <option value="VIEWER">VIEWER</option>
                    <option value="ENGINEER">ENGINEER</option>
                    <option value="ADMIN">ADMIN</option>
                  </select>
                </label>
              </div>

              {registerError && (
                <div
                  style={{
                    marginTop: 14,
                    padding: '10px 12px',
                    borderRadius: 12,
                    border: '1px solid rgba(220,38,38,0.18)',
                    background: 'rgba(220,38,38,0.06)',
                    color: 'var(--ion-color-danger)',
                    fontSize: 12.5,
                  }}
                >
                  {registerError}
                </div>
              )}

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
                <button
                  type="button"
                  onClick={() => {
                    setRegisterOpen(false);
                    setRegisterError(null);
                  }}
                  style={secondaryButtonStyle}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={registering}
                  style={{
                    ...secondaryButtonStyle,
                    background: 'var(--chat-primary)',
                    borderColor: 'var(--chat-primary)',
                    color: 'white',
                    cursor: registering ? 'not-allowed' : 'pointer',
                    opacity: registering ? 0.75 : 1,
                  }}
                >
                  {registering ? 'Creating...' : 'Create User'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {roleEditorUser && (
        <div style={overlayStyle}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Change role dialog"
            style={{ ...sectionCardStyle, width: 'min(100%, 420px)' }}
          >
            <div style={{ fontSize: 17, fontWeight: 800, color: 'var(--chat-text)', marginBottom: 8 }}>
              Change role
            </div>
            <div style={{ color: 'var(--chat-muted)', fontSize: 13.5, lineHeight: 1.6, marginBottom: 14 }}>
              Update the role for <strong style={{ color: 'var(--chat-text)' }}>{roleEditorUser.username}</strong>.
            </div>
            <label>
              <div style={fieldLabelStyle}>Role</div>
              <select
                value={roleDraft}
                onChange={(event) => setRoleDraft(event.target.value as AdminRoleOption)}
                style={selectStyle}
              >
                {USER_ROLE_OPTIONS.map((roleOption) => (
                  <option key={roleOption} value={roleOption}>
                    {roleOption}
                  </option>
                ))}
              </select>
            </label>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
              <button
                type="button"
                onClick={() => setRoleEditorUser(null)}
                style={secondaryButtonStyle}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleRoleChange()}
                disabled={processingKey === `${roleEditorUser.userId}:role`}
                style={{
                  ...secondaryButtonStyle,
                  background: 'rgba(37,99,235,0.12)',
                  borderColor: 'rgba(37,99,235,0.22)',
                  color: 'var(--ion-color-primary)',
                }}
              >
                {processingKey === `${roleEditorUser.userId}:role` ? 'Saving...' : 'Save Role'}
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmDeactivateUser && (
        <div style={overlayStyle}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Deactivate user confirmation"
            style={{ ...sectionCardStyle, width: 'min(100%, 420px)' }}
          >
            <div style={{ fontSize: 17, fontWeight: 800, color: 'var(--chat-text)', marginBottom: 8 }}>
              Deactivate user?
            </div>
            <div style={{ color: 'var(--chat-muted)', fontSize: 13.5, lineHeight: 1.6 }}>
              Deactivate user <strong style={{ color: 'var(--chat-text)' }}>{confirmDeactivateUser.username}</strong>? They will lose access immediately.
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
              <button
                type="button"
                onClick={() => setConfirmDeactivateUser(null)}
                style={secondaryButtonStyle}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleDeactivate(confirmDeactivateUser)}
                disabled={processingKey === `${confirmDeactivateUser.userId}:deactivate`}
                style={{
                  ...secondaryButtonStyle,
                  background: 'rgba(217,119,6,0.16)',
                  borderColor: 'rgba(217,119,6,0.24)',
                  color: 'var(--ion-color-warning)',
                }}
              >
                {processingKey === `${confirmDeactivateUser.userId}:deactivate` ? 'Deactivating...' : 'Confirm'}
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmDeleteUser && (
        <div style={overlayStyle}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Delete user confirmation"
            style={{ ...sectionCardStyle, width: 'min(100%, 420px)' }}
          >
            <div style={{ fontSize: 17, fontWeight: 800, color: 'var(--chat-text)', marginBottom: 8 }}>
              Permanently delete user?
            </div>
            <div style={{ color: 'var(--chat-muted)', fontSize: 13.5, lineHeight: 1.6 }}>
              Permanently delete <strong style={{ color: 'var(--chat-text)' }}>{confirmDeleteUser.username}</strong>? This removes the account and its owned conversations, messages, feedback references, and refresh tokens.
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
              <button
                type="button"
                onClick={() => setConfirmDeleteUser(null)}
                style={secondaryButtonStyle}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleHardDelete(confirmDeleteUser)}
                disabled={processingKey === `${confirmDeleteUser.userId}:delete`}
                style={{
                  ...secondaryButtonStyle,
                  background: 'rgba(220,38,38,0.14)',
                  borderColor: 'rgba(220,38,38,0.24)',
                  color: 'var(--ion-color-danger)',
                }}
              >
                {processingKey === `${confirmDeleteUser.userId}:delete` ? 'Deleting...' : 'Delete Permanently'}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
};

const FeedbackSection: React.FC<{
  token: string;
  userId: string;
  onToast: (message: string, color?: string) => void;
}> = ({ token, userId, onToast }) => {
  const history = useHistory();
  const [feedback, setFeedback] = useState<AdminFeedback[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedPrompts, setExpandedPrompts] = useState<Record<string, boolean>>({});
  const [expandedResponses, setExpandedResponses] = useState<Record<string, boolean>>({});

  const loadFeedback = useCallback(async (background = false) => {
    if (background) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }

    try {
      setError(null);
      const entries = await getDislikedFeedback(token, 20);
      setFeedback(entries);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load feedback.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [token]);

  useEffect(() => {
    void loadFeedback();
  }, [loadFeedback]);

  const openConversation = useCallback((conversationId: string) => {
    localStorage.setItem(getUserActiveConversationStorageKey(userId), conversationId);
    history.push('/chat');
  }, [history, userId]);

  return (
    <section>
      <SectionHeader
        title="Feedback"
        description="Review recent disliked responses and jump directly back into the related conversation."
        onRefresh={() => void loadFeedback(true)}
        refreshing={refreshing}
      />

      {loading ? (
        <div style={{ ...sectionCardStyle, display: 'flex', alignItems: 'center', gap: 10 }}>
          <IonSpinner name="crescent" />
          <span style={{ color: 'var(--chat-muted)' }}>Loading feedback...</span>
        </div>
      ) : error ? (
        <div
          style={{
            ...sectionCardStyle,
            color: 'var(--ion-color-danger)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <IonIcon icon={alertCircleOutline} style={{ fontSize: 18 }} />
          <span>{error}</span>
        </div>
      ) : feedback.length === 0 ? (
        <div style={{ ...sectionCardStyle, color: 'var(--chat-muted)', fontSize: 13.5 }}>
          No disliked responses recorded yet.
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 14 }}>
          {feedback.map((entry) => {
            const promptExpanded = expandedPrompts[entry.id] || false;
            const responseExpanded = expandedResponses[entry.id] || false;

            return (
              <div key={entry.id} style={{ ...sectionCardStyle, padding: 16 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 12,
                    flexWrap: 'wrap',
                    marginBottom: 12,
                  }}
                >
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <span style={intentBadgeStyle(entry.intent)}>{entry.intent || 'UNKNOWN'}</span>
                    <span style={inputBadgeStyle(entry.inputClass)}>
                      {CHAT_BADGE[normalizeInputClassValue(entry.inputClass)].label}
                    </span>
                  </div>
                  <span style={{ color: 'var(--chat-muted)', fontSize: 12.5 }}>
                    {formatRelativeTime(entry.createdAt)}
                  </span>
                </div>

                <div style={{ display: 'grid', gap: 10 }}>
                  <div>
                    <button
                      type="button"
                      onClick={() =>
                        setExpandedPrompts((current) => ({
                          ...current,
                          [entry.id]: !promptExpanded,
                        }))
                      }
                      style={expandButtonStyle}
                    >
                      <span>
                        <strong>Prompt:</strong>{' '}
                        {promptExpanded ? (entry.promptSnapshot || 'Not recorded') : truncateText(entry.promptSnapshot, 150)}
                      </span>
                      <IonIcon icon={promptExpanded ? chevronUpOutline : chevronDownOutline} />
                    </button>
                  </div>

                  <div>
                    <button
                      type="button"
                      onClick={() =>
                        setExpandedResponses((current) => ({
                          ...current,
                          [entry.id]: !responseExpanded,
                        }))
                      }
                      style={expandButtonStyle}
                    >
                      <span>
                        <strong>Response:</strong>{' '}
                        {responseExpanded ? (entry.responseSnapshot || 'Not recorded') : truncateText(entry.responseSnapshot, 150)}
                      </span>
                      <IonIcon icon={responseExpanded ? chevronUpOutline : chevronDownOutline} />
                    </button>
                  </div>
                </div>

                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 12,
                    flexWrap: 'wrap',
                    marginTop: 12,
                  }}
                >
                  <span style={{ color: 'var(--chat-muted)', fontSize: 12.5 }}>
                    Model: {entry.modelName || 'Not recorded'}
                  </span>
                  {entry.conversationId ? (
                    <button
                      type="button"
                      onClick={() => openConversation(entry.conversationId as string)}
                      style={{
                        ...secondaryButtonStyle,
                        padding: '8px 10px',
                        color: 'var(--ion-color-primary)',
                      }}
                    >
                      <span>Conversation</span>
                      <IonIcon icon={openOutline} style={{ fontSize: 13 }} />
                    </button>
                  ) : (
                    <button
                      type="button"
                      disabled
                      onClick={() => onToast('Conversation reference not available', 'medium')}
                      style={{
                        ...secondaryButtonStyle,
                        padding: '8px 10px',
                        color: 'var(--chat-muted-2)',
                        cursor: 'not-allowed',
                        opacity: 0.7,
                      }}
                    >
                      <span>Conversation</span>
                      <IonIcon icon={openOutline} style={{ fontSize: 13 }} />
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
};

const SystemSection: React.FC<{
  token: string;
  onToast: (message: string, color?: string) => void;
}> = ({ token, onToast }) => {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [mcpHealth, setMcpHealth] = useState<McpHealthResponse | null>(null);
  const [info, setInfo] = useState<ActuatorInfoResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (background = false) => {
    if (background) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }

    try {
      setError(null);
      const [nextHealth, nextMcpHealth, nextInfo] = await Promise.all([
        getActuatorHealth(token),
        getMcpHealth(),
        getActuatorInfo(token),
      ]);
      setHealth(nextHealth);
      setMcpHealth(nextMcpHealth);
      setInfo(nextInfo);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load system details.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  const copyAuditCommand = useCallback(async () => {
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard access unavailable');
      }
      await navigator.clipboard.writeText(AUDIT_LOG_COMMAND);
      onToast('Audit query copied', 'success');
    } catch (err) {
      onToast(err instanceof Error ? err.message : 'Could not copy command.', 'danger');
    }
  }, [onToast]);

  return (
    <section>
      <SectionHeader
        title="System"
        description="Read-only runtime status for the backend, MCP proxy, and exposed actuator metadata."
        onRefresh={() => void load(true)}
        refreshing={refreshing}
      />

      {loading ? (
        <div style={{ ...sectionCardStyle, display: 'flex', alignItems: 'center', gap: 10 }}>
          <IonSpinner name="crescent" />
          <span style={{ color: 'var(--chat-muted)' }}>Loading system status...</span>
        </div>
      ) : error ? (
        <div
          style={{
            ...sectionCardStyle,
            color: 'var(--ion-color-danger)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <IonIcon icon={alertCircleOutline} style={{ fontSize: 18 }} />
          <span>{error}</span>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 14 }}>
          <div style={sectionCardStyle}>
            <div style={subsectionTitleStyle}>Health</div>
            <div style={{ marginTop: 12 }}>
              <span style={statusBadgeStyle(health?.status || 'DOWN')}>
                {health?.status || 'UNKNOWN'}
              </span>
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
              {Object.entries(health?.components || {}).map(([component, componentStatus]) => (
                <span key={component} style={statusBadgeStyle(componentStatus.status)}>
                  {component} - {componentStatus.status}
                </span>
              ))}
            </div>
          </div>

          <div style={sectionCardStyle}>
            <div style={subsectionTitleStyle}>MCP Status</div>
            <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <span style={mcpHealth?.reachable ? statusBadgeStyle('UP') : statusBadgeStyle('DOWN')}>
                {mcpHealth?.reachable ? 'reachable' : 'unreachable'}
              </span>
              {mcpHealth?.error && (
                <span style={{ color: 'var(--ion-color-danger)', fontSize: 12.5 }}>{mcpHealth.error}</span>
              )}
            </div>
            <div style={{ marginTop: 12, color: 'var(--chat-muted)', fontSize: 12.5 }}>
              Tool count: {mcpHealth?.toolCount ?? mcpHealth?.tools?.length ?? 0}
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 10 }}>
              {(mcpHealth?.tools || []).map((toolName) => (
                <span key={toolName} style={badgeStyle('var(--chat-surface-2)', 'var(--chat-text)', 'var(--chat-border)')}>
                  {toolName}
                </span>
              ))}
              {(!mcpHealth?.tools || mcpHealth.tools.length === 0) && (
                <span style={{ color: 'var(--chat-muted)', fontSize: 12.5 }}>
                  No MCP tool inventory exposed.
                </span>
              )}
            </div>
          </div>

          <div style={sectionCardStyle}>
            <div style={subsectionTitleStyle}>Environment Info</div>
            {!info || Object.keys(info).length === 0 ? (
              <div style={{ marginTop: 12, color: 'var(--chat-muted)', fontSize: 13.5 }}>
                No actuator info exposed.
              </div>
            ) : (
              <div style={{ marginTop: 12, overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 420 }}>
                  <tbody>
                    {Object.entries(info).map(([key, value], index) => (
                      <tr key={key} style={{ borderTop: index === 0 ? 'none' : '1px solid var(--chat-border)' }}>
                        <td style={{ padding: '10px 8px 10px 0', color: 'var(--chat-muted)', width: 180 }}>
                          {key}
                        </td>
                        <td style={{ padding: '10px 0', color: 'var(--chat-text)' }}>
                          {renderInfoValue(value)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div style={sectionCardStyle}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                flexWrap: 'wrap',
              }}
            >
              <div style={subsectionTitleStyle}>Audit Log</div>
              <button type="button" onClick={() => void copyAuditCommand()} style={secondaryButtonStyle}>
                <IonIcon icon={copyOutline} style={{ fontSize: 13 }} />
                Copy
              </button>
            </div>
            <div style={{ marginTop: 10, color: 'var(--chat-muted)', fontSize: 13.5, lineHeight: 1.6 }}>
              Audit log is HMAC-signed and stored in the database. Access it directly with:
            </div>
            <pre
              style={{
                marginTop: 12,
                padding: 14,
                borderRadius: 14,
                background: 'var(--chat-code-bg)',
                border: '1px solid var(--chat-code-border)',
                color: 'var(--chat-text)',
                overflowX: 'auto',
                fontSize: 12,
                lineHeight: 1.7,
              }}
            >
              <code>{AUDIT_LOG_COMMAND}</code>
            </pre>
          </div>
        </div>
      )}
    </section>
  );
};

const subsectionTitleStyle: React.CSSProperties = {
  fontSize: 16,
  fontWeight: 800,
  color: 'var(--chat-text)',
};

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.38)',
  backdropFilter: 'blur(3px)',
  zIndex: 450,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 16,
};

const closeButtonStyle: React.CSSProperties = {
  position: 'absolute',
  top: 12,
  right: 12,
  width: 32,
  height: 32,
  borderRadius: 10,
  border: '1px solid var(--chat-border)',
  background: 'var(--chat-surface)',
  color: 'var(--chat-muted)',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
};

const fieldLabelStyle: React.CSSProperties = {
  color: 'var(--chat-muted)',
  fontSize: 12,
  marginBottom: 6,
};

const expandButtonStyle: React.CSSProperties = {
  width: '100%',
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 12,
  padding: '10px 12px',
  borderRadius: 12,
  border: '1px solid var(--chat-border)',
  background: 'var(--chat-surface)',
  color: 'var(--chat-text)',
  cursor: 'pointer',
  textAlign: 'left',
  fontSize: 13,
  lineHeight: 1.6,
};
