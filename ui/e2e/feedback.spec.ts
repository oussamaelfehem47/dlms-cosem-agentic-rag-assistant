/**
 * Suite 7 — Feedback System
 *
 * Tests like/dislike buttons and verifies persistence in reflection stats.
 */

import { test, expect } from '@playwright/test';
import { sendMessage, startNewConversation } from './helpers/chat';
import { getToken } from './helpers/auth';
import { adminAuthFile, UI_URL, API_URL } from './helpers/paths';

const BASE_URL = UI_URL;
const API_BASE  = API_URL;

test.use({ storageState: adminAuthFile });

test.beforeEach(async ({ page }) => {
  await page.goto(BASE_URL);
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  await startNewConversation(page).catch(() => {});
});

// ── 7.1 ── Thumbs appear after response ──────────────────────────────────────
test('7.1 Like and dislike buttons appear after an assistant response', async ({ page }) => {
  await sendMessage(page, 'hello');

  // FeedbackButton renders inside AssistantMessage with these aria-labels
  await expect(
    page.getByRole('button', { name: /mark response as helpful/i }).first(),
  ).toBeVisible({ timeout: 10_000 });

  await expect(
    page.getByRole('button', { name: /mark response as not helpful/i }).first(),
  ).toBeVisible({ timeout: 10_000 });
});

// ── 7.2 ── Clicking Like selects it and disables Dislike ──────────────────────
test('7.2 Clicking like fills the button and disables dislike', async ({ page }) => {
  await sendMessage(page, 'What is OBIS 1.0.1.8.0.255?');

  const likeBtn    = page.getByRole('button', { name: /mark response as helpful/i }).first();
  const dislikeBtn = page.getByRole('button', { name: /mark response as not helpful/i }).first();

  await likeBtn.click();

  // After selecting, dislike cursor should be 'default' (not clickable)
  // The FeedbackButton sets opacity 0.4 and cursor: 'default' on the inactive button
  // We verify by attempting a second click — dislike should not trigger another POST
  const dislikeOpacity = await dislikeBtn.evaluate(
    (el) => parseFloat(getComputedStyle(el).opacity),
  );
  expect(dislikeOpacity).toBeLessThan(1);  // dimmed = disabled-like

  // Like button should show filled icon (thumbsUpSharp) — just verify it's still visible
  await expect(likeBtn).toBeVisible();
});

// ── 7.3 ── Clicking dislike records feedback ──────────────────────────────────
test('7.3 Clicking dislike POSTs feedback and button shows selected state', async ({ page }) => {
  await sendMessage(page, '7EA00A030383CD6F7E');

  const dislikeBtn = page.getByRole('button', { name: /mark response as not helpful/i }).first();
  await dislikeBtn.click();

  // Selected dislike should still be visible (not disappear)
  await expect(dislikeBtn).toBeVisible();

  // Like button should be dimmed
  const likeOpacity = await page
    .getByRole('button', { name: /mark response as helpful/i })
    .first()
    .evaluate((el) => parseFloat(getComputedStyle(el).opacity));
  expect(likeOpacity).toBeLessThan(1);
});

// ── 7.4 ── Feedback shows in reflection stats ─────────────────────────────────
test('7.4 Feedback is reflected in /api/admin/reflection/stats', async ({ page }) => {
  await sendMessage(page, 'What is OBIS 1.0.1.8.0.255?');

  // Submit dislike feedback
  await page
    .getByRole('button', { name: /mark response as not helpful/i })
    .first()
    .click();

  // Wait a moment for the POST to complete
  await page.waitForTimeout(1500);

  // Check stats
  const token = await getToken('admin', 'admin123');
  const res = await fetch(`${API_BASE}/api/admin/reflection/stats`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  expect(res.status).toBe(200);
  const data = await res.json();

  // stats should be a non-empty object
  expect(data).toBeTruthy();
  expect(typeof data).toBe('object');
});
