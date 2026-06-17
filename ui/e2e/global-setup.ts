import { chromium } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { AUTH_DIR, UI_URL, API_URL } from './helpers/paths';

const UI_BASE  = UI_URL;   // Vite dev server — browser navigation
const API_BASE = API_URL;  // Nginx/Docker — direct Node.js API calls

async function apiPost(
  url: string,
  body: unknown,
  token?: string,
): Promise<{ ok: boolean; status: number; data: unknown }> {
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data };
}

export async function getToken(identifier: string, password: string): Promise<string | null> {
  try {
    const result = await apiPost(`${API_BASE}/api/auth/login`, { email: identifier, password });
    if (!result.ok) return null;
    return (result.data as Record<string, string>)?.access_token ?? null;
  } catch {
    return null;
  }
}

async function loginAndSaveState(
  identifier: string,
  password: string,
  file: string,
): Promise<void> {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto(UI_BASE);

  // Wait for login form
  await page.locator('input[placeholder="you@company.com"]').waitFor({ timeout: 10_000 });
  await page.locator('input[placeholder="you@company.com"]').fill(identifier);
  await page.locator('input[type="password"]').fill(password);
  await page.locator('button[type="submit"]').click();

  // Wait until chat interface is visible (not login page)
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });

  await context.storageState({ path: file });
  await browser.close();
}

export default async function globalSetup() {
  // ── 1. Verify stack health ────────────────────────────────────────────────
  let healthy = false;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      const res = await fetch(`${API_BASE}/api/actuator/health`);
      const data = await res.json();
      if (data.status === 'UP') {
        healthy = true;
        break;
      }
    } catch {
      // not yet up
    }
    if (attempt < 5) await new Promise((r) => setTimeout(r, 3000));
  }

  if (!healthy) {
    throw new Error(
      'Backend health check failed — make sure the stack is running:\n  docker-compose up -d\n  Then wait ~30 s and retry.',
    );
  }

  // ── 2. Auth directory ─────────────────────────────────────────────────────
  if (!fs.existsSync(AUTH_DIR)) fs.mkdirSync(AUTH_DIR, { recursive: true });

  // ── 3. Save admin storageState ────────────────────────────────────────────
  await loginAndSaveState('admin', 'admin123', path.join(AUTH_DIR, 'admin.json'));

  // ── 4. Get admin token for API calls ─────────────────────────────────────
  const adminToken = await getToken('admin', 'admin123');
  if (!adminToken) throw new Error('Could not obtain admin token after login');

  // ── 5. Create test users (best-effort, ignore if already exist) ───────────
  const testUsers = [
    { email: 'engineer_test@dlms.test', username: 'engineer_test', password: 'test123456', role: 'ENGINEER' },
    { email: 'viewer_test@dlms.test',   username: 'viewer_test',   password: 'test123456', role: 'VIEWER'   },
  ];

  for (const u of testUsers) {
    const result = await apiPost(`${API_BASE}/api/auth/register`, u, adminToken);
    if (!result.ok && result.status !== 409) {
      // eslint-disable-next-line no-console
      console.warn(`[setup] Could not create user ${u.username}: ${JSON.stringify(result.data)}`);
    }
  }

  // ── 6. Save engineer / viewer storageStates ───────────────────────────────
  await loginAndSaveState('engineer_test', 'test123456', path.join(AUTH_DIR, 'engineer.json'));
  await loginAndSaveState('viewer_test',   'test123456', path.join(AUTH_DIR, 'viewer.json'));
}
