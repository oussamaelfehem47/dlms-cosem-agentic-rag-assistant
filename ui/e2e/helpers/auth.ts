import { Page } from '@playwright/test';
import { UI_URL, API_URL } from './paths';

const BASE_URL = UI_URL;
const API_BASE  = API_URL;

/**
 * Login via the real UI form and wait for the chat interface to load.
 */
export async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto(BASE_URL);
  await page.locator('input[placeholder="you@company.com"]').waitFor({ timeout: 10_000 });
  await page.locator('input[placeholder="you@company.com"]').fill(username);
  await page.locator('input[type="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
}

/**
 * POST /api/auth/login directly and return the accessToken.
 */
export async function getToken(username: string, password: string): Promise<string> {
  const res = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: username, password }),
  });

  if (!res.ok) {
    throw new Error(`Login failed for ${username}: HTTP ${res.status}`);
  }

  const data = await res.json();
  if (!data.access_token) throw new Error(`No access_token in login response for ${username}`);
  return data.access_token as string;
}

/**
 * Logout via the header Sign Out button and wait for the login form to reappear.
 */
export async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: /sign out/i }).click();
  // State-based app: login form reappears in place (no URL change)
  await page.locator('button[type="submit"]').waitFor({ state: 'visible', timeout: 10_000 });
}
