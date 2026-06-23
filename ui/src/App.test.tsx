import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { useAuth } from './hooks/useAuth';

vi.mock('./hooks/useAuth', () => ({
  useAuth: vi.fn(),
}));

vi.mock('./pages/LoginPage', () => ({
  LoginPage: () => <div>Login Page</div>,
}));

vi.mock('./pages/ChatPage', () => ({
  ChatPage: () => <div>Chat Page</div>,
}));

vi.mock('./pages/AccountSettingsPage', () => ({
  AccountSettingsPage: () => <div>Account Page</div>,
}));

vi.mock('./pages/AdminPage', () => ({
  AdminPage: ({ section }: { section?: string }) => (
    <div>Admin Page {section || 'overview'}</div>
  ),
}));

const mockedUseAuth = vi.mocked(useAuth);

function buildAuthState(overrides?: Partial<ReturnType<typeof useAuth>>) {
  return {
    user: null,
    token: null,
    isAuthenticated: false,
    isLoading: false,
    isInitialized: true,
    error: null,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refreshToken: vi.fn(),
    ...overrides,
  };
}

describe('App routing', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
  });

  afterEach(() => {
    vi.clearAllMocks();
    window.history.pushState({}, '', '/');
  });

  it('redirects unauthenticated /admin requests to /login', async () => {
    mockedUseAuth.mockReturnValue(buildAuthState());
    window.history.pushState({}, '', '/admin');

    render(<App />);

    expect(await screen.findByText('Login Page')).toBeInTheDocument();
    await waitFor(() => {
      expect(window.location.pathname).toBe('/login');
    });
  });

  it('redirects non-admin /admin requests to /chat', async () => {
    mockedUseAuth.mockReturnValue(buildAuthState({
      token: 'viewer-token',
      isAuthenticated: true,
      user: {
        id: 'user-1',
        username: 'viewer',
        email: 'viewer@example.com',
        role: 'VIEWER',
      },
    }));
    window.history.pushState({}, '', '/admin');

    render(<App />);

    expect(await screen.findByText('Chat Page')).toBeInTheDocument();
    await waitFor(() => {
      expect(window.location.pathname).toBe('/chat');
    });
    expect(await screen.findByText('Admin access required')).toBeInTheDocument();
  });

  it('renders the standalone admin route for admins', async () => {
    mockedUseAuth.mockReturnValue(buildAuthState({
      token: 'admin-token',
      isAuthenticated: true,
      user: {
        id: 'admin-1',
        username: 'admin',
        email: 'admin@example.com',
        role: 'ADMIN',
      },
    }));
    window.history.pushState({}, '', '/admin');

    render(<App />);

    expect(await screen.findByText('Admin Page overview')).toBeInTheDocument();
    await waitFor(() => {
      expect(window.location.pathname).toBe('/admin/overview');
    });
  });
});
