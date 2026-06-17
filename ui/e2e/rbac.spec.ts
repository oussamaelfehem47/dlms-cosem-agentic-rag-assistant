/**
 * Suite 6 — RBAC and Access Control
 *
 * Verifies role enforcement at both UI and API levels.
 */

import { test, expect } from '@playwright/test';
import { sendMessage, startNewConversation } from './helpers/chat';
import { getToken } from './helpers/auth';
import { viewerAuthFile, adminAuthFile, UI_URL, API_URL } from './helpers/paths';

const BASE_URL = UI_URL;
const API_BASE  = API_URL;

// ── 6.1 ── Viewer can decode ─────────────────────────────────────────────────
test('6.1 Viewer can send queries and receive responses (not 403)', async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: viewerAuthFile });
  const page = await ctx.newPage();
  await page.goto(BASE_URL);
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  await startNewConversation(page).catch(() => {});

  const response = await sendMessage(page, 'What is OBIS 1.0.1.8.0.255?');
  expect(response.trim().length).toBeGreaterThan(0);
  expect(response).not.toMatch(/403|forbidden/i);

  await ctx.close();
});

// ── 6.2 ── Admin can access reflection stats ──────────────────────────────────
test('6.2 Admin can access /api/admin/reflection/stats (HTTP 200)', async () => {
  const token = await getToken('admin', 'admin123');

  const res = await fetch(`${API_BASE}/api/admin/reflection/stats`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  expect(res.status).toBe(200);

  const data = await res.json();
  expect(data).toBeTruthy();
});

// ── 6.3 ── Viewer cannot access reflection stats ──────────────────────────────
test('6.3 Viewer receives 403 on /api/admin/reflection/stats', async () => {
  const token = await getToken('viewer_test', 'test123456');

  const res = await fetch(`${API_BASE}/api/admin/reflection/stats`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  expect(res.status).toBe(403);
});

// ── 6.4 ── Role visible in UI ─────────────────────────────────────────────────
test('6.4 Account settings page shows the correct role label', async ({ browser }) => {
  // Admin
  const adminCtx = await browser.newContext({ storageState: adminAuthFile });
  const adminPage = await adminCtx.newPage();
  await adminPage.goto(BASE_URL);
  await adminPage.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  await adminPage.getByRole('button', { name: /open account and settings/i }).click();
  const adminSettings = await adminPage.innerText('body');
  expect(adminSettings).toMatch(/admin/i);
  await adminCtx.close();

  // Viewer
  const viewerCtx = await browser.newContext({ storageState: viewerAuthFile });
  const viewerPage = await viewerCtx.newPage();
  await viewerPage.goto(BASE_URL);
  await viewerPage.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  await viewerPage.getByRole('button', { name: /open account and settings/i }).click();
  const viewerSettings = await viewerPage.innerText('body');
  expect(viewerSettings).toMatch(/viewer/i);
  await viewerCtx.close();
});
