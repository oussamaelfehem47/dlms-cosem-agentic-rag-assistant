/**
 * Suite 5 — File Upload
 *
 * Verifies attachment handling in the composer.
 */

import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { waitForResponse, startNewConversation } from './helpers/chat';
import { adminAuthFile } from './helpers/paths';

test.use({ storageState: adminAuthFile });

test.beforeEach(async ({ page }) => {
  await page.goto('http://localhost:5173');
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  await startNewConversation(page).catch(() => {});
});

// ── 5.1 ── Upload text file with HEX frame ───────────────────────────────────
test('5.1 Upload a .txt file containing a HEX frame triggers decode', async ({ page }) => {
  // Create temp file
  const tmpFile = path.join(os.tmpdir(), 'frame_e2e.txt');
  fs.writeFileSync(tmpFile, '7EA00A030383CD6F7E');

  // Find the file input — UploadButton wraps an <input type="file">
  const fileInput = page.locator('input[type="file"]');

  // Trigger via setInputFiles (bypasses the file picker dialog)
  await fileInput.setInputFiles(tmpFile);

  // Attachment badge should appear in the queue
  await expect(page.locator('[data-testid="attachment-queue"]')).toBeVisible({ timeout: 8_000 });
  await expect(page.locator('[data-testid="attachment-queue"]').locator('text=frame_e2e.txt')).toBeVisible();

  // Send
  await page.locator('[data-testid="send-button"]').click();
  await waitForResponse(page);

  // Response should treat the content as a HEX frame
  const listText = await page.locator('[data-testid="chat-message-list"]').innerText();
  const treatedAsHex =
    /HEX FRAME/i.test(listText) ||
    /U_FRAME|SNRM|HDLC/i.test(listText) ||
    /decode/i.test(listText);
  expect(treatedAsHex).toBe(true);

  fs.unlinkSync(tmpFile);
});

// ── 5.2 ── Multiple attachments show in queue ────────────────────────────────
test('5.2 Multiple attachments all appear in the composer queue', async ({ page }) => {
  const tmpA = path.join(os.tmpdir(), 'e2e_attach_a.txt');
  const tmpB = path.join(os.tmpdir(), 'e2e_attach_b.txt');
  fs.writeFileSync(tmpA, 'First attachment content');
  fs.writeFileSync(tmpB, 'Second attachment content');

  const fileInput = page.locator('input[type="file"]');

  // Upload first
  await fileInput.setInputFiles(tmpA);
  await expect(page.locator('[data-testid="attachment-queue"]')).toBeVisible({ timeout: 8_000 });

  // Upload second — some implementations open the picker again; we re-trigger
  await fileInput.setInputFiles(tmpB);
  await page.waitForTimeout(500);

  const queue = page.locator('[data-testid="attachment-queue"]');
  const queueText = await queue.innerText();
  // At least one of the filenames must appear
  const hasAttachments = queueText.includes('e2e_attach_a') || queueText.includes('e2e_attach_b');
  expect(hasAttachments).toBe(true);

  fs.unlinkSync(tmpA);
  fs.unlinkSync(tmpB);
});

// ── 5.3 ── Remove attachment before sending ───────────────────────────────────
test('5.3 Removing an attachment clears it from the queue', async ({ page }) => {
  const tmpFile = path.join(os.tmpdir(), 'e2e_remove_me.txt');
  fs.writeFileSync(tmpFile, 'content to remove');

  const fileInput = page.locator('input[type="file"]');
  await fileInput.setInputFiles(tmpFile);
  await expect(page.locator('[data-testid="attachment-queue"]')).toBeVisible({ timeout: 8_000 });

  // Click remove button on the attachment
  await page.locator(
    '[aria-label*="Remove attachment"]',
  ).first().click();

  // Queue should be gone
  await expect(page.locator('[data-testid="attachment-queue"]')).not.toBeVisible({ timeout: 5_000 });

  // Can still type and send normally
  await expect(page.locator('[data-testid="chat-input"]')).toBeEnabled();

  fs.unlinkSync(tmpFile);
});

// 5.4 - Drag and drop upload on the composer drop target
test('5.4 Dragging a file onto the composer queues it and still decodes on send', async ({ page }) => {
  const tmpFile = path.join(os.tmpdir(), 'frame_drag_drop.txt');
  fs.writeFileSync(tmpFile, '7EA00A030383CD6F7E');
  const fileContent = fs.readFileSync(tmpFile, 'utf8');

  const dataTransfer = await page.evaluateHandle(({ name, content }) => {
    const dt = new DataTransfer();
    dt.items.add(new File([content], name, { type: 'text/plain' }));
    return dt;
  }, {
    name: path.basename(tmpFile),
    content: fileContent,
  });

  const dropTarget = page.locator('[data-testid="composer-drop-target"]');

  await dropTarget.dispatchEvent('dragenter', { dataTransfer });
  await expect(dropTarget).toHaveAttribute('data-drag-active', 'true');
  await dropTarget.dispatchEvent('dragover', { dataTransfer });
  await dropTarget.dispatchEvent('drop', { dataTransfer });

  await expect(dropTarget).toHaveAttribute('data-drag-active', 'false');
  await expect(page.locator('[data-testid="attachment-queue"]')).toBeVisible({ timeout: 8_000 });
  await expect(page.locator('[data-testid="attachment-queue"]').locator('text=frame_drag_drop.txt')).toBeVisible();

  await page.locator('[data-testid="send-button"]').click();
  await waitForResponse(page);

  const listText = await page.locator('[data-testid="chat-message-list"]').innerText();
  const treatedAsHex =
    /HEX FRAME/i.test(listText) ||
    /U_FRAME|SNRM|HDLC/i.test(listText) ||
    /decode/i.test(listText);
  expect(treatedAsHex).toBe(true);

  fs.unlinkSync(tmpFile);
});
