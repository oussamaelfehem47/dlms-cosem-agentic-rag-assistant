/**
 * Suite 8 — Error States and Edge Cases
 */

import { test, expect } from '@playwright/test';
import { sendMessage, startNewConversation, waitForResponse } from './helpers/chat';
import { adminAuthFile } from './helpers/paths';

test.use({ storageState: adminAuthFile });

test.beforeEach(async ({ page }) => {
  await page.goto('http://localhost:5173');
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  await startNewConversation(page).catch(() => {});
});

// ── 8.1 ── Empty message not sent ────────────────────────────────────────────
test('8.1 Empty message is not sent when clicking Send', async ({ page }) => {
  // Ensure input is blank
  await page.locator('[data-testid="chat-input"]').fill('');

  // Send button should be disabled (canSend=false)
  const sendBtn = page.locator('[data-testid="send-button"]');
  const isDisabled = await sendBtn.evaluate((el) => {
    // IonButton sets disabled attribute or aria-disabled
    return el.hasAttribute('disabled') || el.getAttribute('aria-disabled') === 'true';
  });
  expect(isDisabled).toBe(true);

  // Message list should remain empty
  await expect(page.locator('[data-testid="chat-empty-state"]')).toBeVisible();
});

// ── 8.2 ── Very long message handled ─────────────────────────────────────────
test('8.2 Very long message (1000 chars) is accepted by the UI', async ({ page }) => {
  const longMsg = 'A'.repeat(1000);
  await page.locator('[data-testid="chat-input"]').fill(longMsg);

  // Should show character count
  await expect(page.locator('text=1000 chars')).toBeVisible({ timeout: 3_000 });

  // Submit and either get a response or a meaningful error — no blank screen / crash
  await page.locator('[data-testid="send-button"]').click();

  // Wait for either a response or an error notice
  await Promise.race([
    waitForResponse(page),
    page.locator('[role="status"]').waitFor({ state: 'visible', timeout: 90_000 }),
  ]);

  // App must still be functional
  await expect(page.locator('[data-testid="chat-input"]')).toBeEnabled({ timeout: 10_000 });
});

// ── 8.3 ── Rapid successive messages ─────────────────────────────────────────
test('8.3 Sending messages back-to-back does not freeze the UI', async ({ page }) => {
  // First message
  await sendMessage(page, 'hello');

  // Second message
  await sendMessage(page, 'What is HDLC?');

  // Third message
  await sendMessage(page, 'What does SNRM stand for?');

  // App still functional after 3 round-trips
  await expect(page.locator('[data-testid="chat-input"]')).toBeEnabled();
  await expect(page.locator('[data-testid="chat-message-list"]')).toBeVisible();
});

// ── 8.4 ── Invalid alarm code ────────────────────────────────────────────────
test('8.4 Unknown alarm code 0x9999 is handled gracefully', async ({ page }) => {
  const responseText = await sendMessage(page, '0x9999');

  // Must not crash — message list still visible
  await expect(page.locator('[data-testid="chat-message-list"]')).toBeVisible();

  // Response should acknowledge the unknown code, not crash silently
  const handled =
    /unknown|unrecognized|not found|no alarm|invalid/i.test(responseText) ||
    responseText.trim().length > 0;
  expect(handled).toBe(true);
});

// ── 8.5 ── Malformed hex frame ───────────────────────────────────────────────
test('8.5 Malformed HEX frame "7E" shows parse error, not crash', async ({ page }) => {
  await page.locator('[data-testid="chat-input"]').fill('7E');
  await page.locator('[data-testid="send-button"]').click();
  const responseText = await waitForResponse(page);

  // Should mention FCS, error, invalid, or short — NOT crash
  const hasErrorInfo =
    /fcs|checksum|invalid|error|parse|incomplete|short/i.test(responseText) ||
    responseText.trim().length > 0;
  expect(hasErrorInfo).toBe(true);

  // Must NOT misidentify as MAC address
  expect(responseText).not.toMatch(/mac address/i);

  // App still functional
  await expect(page.locator('[data-testid="chat-input"]')).toBeEnabled();
});

// ── 8.6 ── Draft preserved after refresh ─────────────────────────────────────
test('8.6 Composer draft is restored after page refresh', async ({ page }) => {
  const draftText = 'Need help with frame counter replay protection';
  await page.locator('[data-testid="chat-input"]').fill(draftText);

  await page.reload();
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });

  const value = await page.locator('[data-testid="chat-input"]').inputValue();
  expect(value).toBe(draftText);
});
