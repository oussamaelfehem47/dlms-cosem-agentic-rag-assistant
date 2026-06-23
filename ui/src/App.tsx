import React from 'react';
import { IonApp, IonRouterOutlet, IonSpinner } from '@ionic/react';
import { IonReactRouter } from '@ionic/react-router';
import { Redirect, Route, RouteComponentProps } from 'react-router-dom';
import { useAuth } from './hooks/useAuth';
import { LoginPage } from './pages/LoginPage';
import { ChatPage } from './pages/ChatPage';
import { AccountSettingsPage } from './pages/AccountSettingsPage';
import { AdminPage } from './pages/AdminPage';

import '@ionic/react/css/core.css';
import '@ionic/react/css/normalize.css';
import '@ionic/react/css/structure.css';
import '@ionic/react/css/typography.css';
import '@ionic/react/css/padding.css';
import '@ionic/react/css/float-elements.css';
import '@ionic/react/css/text-alignment.css';
import '@ionic/react/css/text-transformation.css';
import '@ionic/react/css/flex-utils.css';
import '@ionic/react/css/display.css';
import './theme/variables.css';

function LoadingShell() {
  return (
    <IonApp>
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          height: '100vh',
          background: 'var(--chat-bg)',
        }}
      >
        <IonSpinner />
      </div>
    </IonApp>
  );
}

function normalizeRole(role?: string | null): string {
  return (role || '').toUpperCase();
}

function getLoginRedirectTarget(state: unknown): string {
  if (!state || typeof state !== 'object') {
    return '/chat';
  }

  const routeState = state as {
    from?: string | { pathname?: string };
  };
  const from = routeState.from;

  if (typeof from === 'string' && from.startsWith('/')) {
    return from;
  }

  if (from && typeof from === 'object' && typeof from.pathname === 'string' && from.pathname.startsWith('/')) {
    return from.pathname;
  }

  return '/chat';
}

function buildLoginRedirectPath(routeProps: RouteComponentProps): string {
  const { pathname, search } = routeProps.location;
  return `${pathname}${search}`;
}

interface AuthenticatedChatRouteProps {
  routeProps: RouteComponentProps;
  token: string;
  userId: string;
  onLogout: () => Promise<void> | void;
  onOpenAccountSettings: () => void;
  refreshToken: () => Promise<boolean>;
  showAdminPanelLink: boolean;
  onOpenAdminPanel: () => void;
  onShowAdminToast: () => void;
}

const AuthenticatedChatRoute: React.FC<AuthenticatedChatRouteProps> = ({
  routeProps,
  token,
  userId,
  onLogout,
  onOpenAccountSettings,
  refreshToken,
  showAdminPanelLink,
  onOpenAdminPanel,
  onShowAdminToast,
}) => {
  const queryParams = React.useMemo(
    () => new URLSearchParams(routeProps.location.search),
    [routeProps.location.search],
  );
  const adminAccessRequired = queryParams.get('adminAccessRequired') === '1';

  React.useEffect(() => {
    if (!adminAccessRequired) {
      return;
    }

    onShowAdminToast();
    const nextParams = new URLSearchParams(routeProps.location.search);
    nextParams.delete('adminAccessRequired');
    routeProps.history.replace({
      pathname: routeProps.location.pathname,
      search: nextParams.toString() ? `?${nextParams.toString()}` : '',
      hash: routeProps.location.hash,
    });
  }, [adminAccessRequired, onShowAdminToast, routeProps.history, routeProps.location.hash, routeProps.location.pathname, routeProps.location.search]);

  return (
    <>
      <ChatPage
        token={token}
        userId={userId}
        onLogout={onLogout}
        onOpenAccountSettings={onOpenAccountSettings}
        refreshToken={refreshToken}
        showAdminPanelLink={showAdminPanelLink}
        onOpenAdminPanel={onOpenAdminPanel}
      />
    </>
  );
};

interface AdminAccessToastPresenterProps {
  trigger: number;
}

const AdminAccessToastPresenter: React.FC<AdminAccessToastPresenterProps> = ({ trigger }) => {
  const [visible, setVisible] = React.useState(false);
  const dismissTimeoutRef = React.useRef<number | null>(null);

  React.useEffect(() => {
    if (trigger === 0) {
      return;
    }

    setVisible(true);
    if (dismissTimeoutRef.current !== null) {
      window.clearTimeout(dismissTimeoutRef.current);
    }
    dismissTimeoutRef.current = window.setTimeout(() => {
      setVisible(false);
      dismissTimeoutRef.current = null;
    }, 2200);
  }, [trigger]);

  React.useEffect(() => {
    return () => {
      if (dismissTimeoutRef.current !== null) {
        window.clearTimeout(dismissTimeoutRef.current);
      }
    };
  }, []);

  if (!visible) {
    return null;
  }

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: 'fixed',
        top: 20,
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 30000,
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '12px 16px',
        borderRadius: 14,
        border: '1px solid rgba(214, 162, 44, 0.35)',
        background: 'rgba(214, 162, 44, 0.95)',
        color: 'var(--ion-color-warning-contrast, #1f1a10)',
        boxShadow: '0 16px 34px rgba(12, 16, 24, 0.28)',
        fontSize: 14,
        fontWeight: 700,
        letterSpacing: '0.01em',
        maxWidth: 'min(420px, calc(100vw - 32px))',
        width: 'max-content',
      }}
    >
      <span aria-hidden="true">!</span>
      <span>Admin access required</span>
    </div>
  );
};

const App: React.FC = () => {
  const {
    user,
    token,
    isLoading,
    isInitialized,
    error,
    login,
    logout,
    refreshToken,
  } = useAuth();
  const [adminToastTrigger, setAdminToastTrigger] = React.useState(0);
  const isAdmin = normalizeRole(user?.role) === 'ADMIN';

  const openAdminToast = React.useCallback(() => {
    setAdminToastTrigger((current) => current + 1);
  }, []);

  if (isLoading || !isInitialized) {
    return <LoadingShell />;
  }

  const renderLogin = (routeProps: RouteComponentProps) => {
    if (token) {
      return <Redirect to={getLoginRedirectTarget(routeProps.location.state)} />;
    }

    return (
      <LoginPage
        onLogin={login}
        isLoading={isLoading}
        error={error}
      />
    );
  };

  const renderChat = (routeProps: RouteComponentProps) => {
    if (!token) {
      return (
        <Redirect
          to={{
            pathname: '/login',
            state: { from: buildLoginRedirectPath(routeProps) },
          }}
        />
      );
    }

    return (
      <AuthenticatedChatRoute
        routeProps={routeProps}
        token={token}
        userId={user?.id || ''}
        onLogout={logout}
        onOpenAccountSettings={() => routeProps.history.push('/account')}
        refreshToken={refreshToken}
        showAdminPanelLink={isAdmin}
        onOpenAdminPanel={() => routeProps.history.push('/admin/overview')}
        onShowAdminToast={openAdminToast}
      />
    );
  };

  const renderAccount = (routeProps: RouteComponentProps) => {
    if (!token) {
      return (
        <Redirect
          to={{
            pathname: '/login',
            state: { from: buildLoginRedirectPath(routeProps) },
          }}
        />
      );
    }

    return (
      <AccountSettingsPage
        token={token}
        userId={user?.id || ''}
        username={user?.username}
        onBack={() => routeProps.history.push('/chat')}
        onLogout={logout}
      />
    );
  };

  const renderAdminSection = (
    routeProps: RouteComponentProps<{ section?: string }>
  ) => {
    if (!token) {
      return (
        <Redirect
          to={{
            pathname: '/login',
            state: { from: buildLoginRedirectPath(routeProps) },
          }}
        />
      );
    }

    if (!isAdmin) {
      return (
        <Redirect
          to={{
            pathname: '/chat',
            search: '?adminAccessRequired=1',
          }}
        />
      );
    }

    return (
      <AdminPage
        token={token}
        userId={user?.id || ''}
        username={user?.username}
        role={user?.role}
        section={routeProps.match.params.section}
      />
    );
  };

  const renderAdminLanding = (routeProps: RouteComponentProps) => {
    if (!token) {
      return (
        <Redirect
          to={{
            pathname: '/login',
            state: { from: buildLoginRedirectPath(routeProps) },
          }}
        />
      );
    }

    if (!isAdmin) {
      return (
        <Redirect
          to={{
            pathname: '/chat',
            search: '?adminAccessRequired=1',
          }}
        />
      );
    }

    return <Redirect to="/admin/overview" />;
  };

  return (
    <IonApp>
      <IonReactRouter>
        <IonRouterOutlet>
          <Route exact path="/login" render={renderLogin} />
          <Route exact path="/chat" render={renderChat} />
          <Route exact path="/account" render={renderAccount} />
          <Route exact path="/admin" render={renderAdminLanding} />
          <Route exact path="/admin/:section" render={renderAdminSection} />
          <Route exact path="/">
            <Redirect to={token ? '/chat' : '/login'} />
          </Route>
          <Route>
            <Redirect to={token ? '/chat' : '/login'} />
          </Route>
        </IonRouterOutlet>
        <AdminAccessToastPresenter trigger={adminToastTrigger} />
      </IonReactRouter>
    </IonApp>
  );
};

export default App;
