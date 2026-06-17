import { getToken } from './global-setup';
import { API_URL } from './helpers/paths';

const BASE_URL = API_URL;

async function apiDelete(url: string, token: string): Promise<void> {
  try {
    await fetch(url, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch {
    // best-effort
  }
}

async function apiGet(url: string, token: string): Promise<unknown> {
  try {
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
    return res.ok ? res.json() : null;
  } catch {
    return null;
  }
}

export default async function globalTeardown() {
  const adminToken = await getToken('admin', 'admin123');
  if (!adminToken) {
    // eslint-disable-next-line no-console
    console.warn('[teardown] Could not obtain admin token - skipping cleanup');
    return;
  }

  const users = await apiGet(`${BASE_URL}/api/admin/users`, adminToken) as Array<{ id: string; username: string }> | null;
  if (Array.isArray(users)) {
    for (const user of users) {
      if (user.username === 'engineer_test' || user.username === 'viewer_test' || user.username === 'test_new_user_e2e') {
        await apiDelete(`${BASE_URL}/api/admin/users/${user.id}`, adminToken);
      }
    }
  }
}
