/**
 * Suite 2 — Core Chat Flows
 *
 * All tests send real messages to the live backend and assert on actual
 * responses (no mocking). Uses admin storageState to skip login.
 */

import { test, expect } from '@playwright/test';
import { sendMessage, waitForResponse, startNewConversation } from './helpers/chat';
import { adminAuthFile } from './helpers/paths';

test.use({ storageState: adminAuthFile });

test.beforeEach(async ({ page }) => {
  await page.goto('http://localhost:5173');
  // Wait for chat to be ready
  await page.locator('[data-testid="chat-input"]').waitFor({ state: 'visible', timeout: 20_000 });
  // Start a fresh conversation so tests don't bleed into each other
  await startNewConversation(page).catch(() => { /* first test — no sidebar needed */ });
});

// ── 2.1 ── Greeting ─────────────────────────────────────────────────────────
test('2.1 Greeting response is non-empty and shows NO source citations', async ({ page }) => {
  await sendMessage(page, 'hello');

  const listText = await page.locator('[data-testid="chat-message-list"]').innerText();
  expect(listText.trim().length).toBeGreaterThan(0);
  // Should not be the output-filter block message
  expect(listText).not.toContain('The response adheres strictly to guidelines');
  // Streaming indicator must be gone
  await expect(page.locator('text=Generating answer...')).not.toBeVisible();

  // Casual greetings must NOT show a sources block (implemented in this sprint)
  await expect(page.locator('[data-testid="assistant-sources"]')).not.toBeVisible();
});

test('2.1a Capability prompts and help follow-ups stay on the assistant-help path', async ({ page }) => {
  const capabilityReply = await sendMessage(page, 'what can you do?');

  expect(capabilityReply).toMatch(/I can help with DLMS\/COSEM questions/i);
  expect(capabilityReply).toMatch(/HDLC frame decode/i);
  await expect(page.locator('[data-testid="assistant-sources"]')).not.toBeVisible();

  const followUpReply = await sendMessage(page, 'tell me more');

  expect(followUpReply).toMatch(/AXDR\/APDU payload/i);
  expect(followUpReply).toMatch(/alarm codes such as 0x1342/i);
  await expect(page.locator('[data-testid="assistant-sources"]')).not.toBeVisible();
});

// ── 2.2 ── OBIS query ───────────────────────────────────────────────────────
test('2.2 QUERY intent — OBIS lookup returns relevant answer with structured sources', async ({ page }) => {
  const listText = await sendMessage(page, 'What is OBIS 1.0.1.8.0.255?');

  // Should mention active energy or import or the OBIS code itself
  const mentionsObis =
    /active.?energy/i.test(listText) ||
    /import/i.test(listText) ||
    /1\.0\.1\.8\.0\.255/.test(listText);
  expect(mentionsObis).toBe(true);

  // Should NOT route as SICONIA
  expect(listText).not.toMatch(/SICONIA alarm/i);

  // Technical OBIS query MUST show structured source citations (not casual greeting path)
  expect(listText).toMatch(/OBIS Resolver \(KG\)/i);
});

test('2.2a AXDR phrasing convergence stays on the same deterministic date-time interpretation', async ({ page }) => {
  const prompts = [
    '1907E80416010E1E0000003C00',
    'Explain AXDR payload 1907E80416010E1E0000003C00',
    'payload 1907E80416010E1E0000003C00',
  ];

  for (const [index, prompt] of prompts.entries()) {
    if (index > 0) {
      await startNewConversation(page);
    }

    const reply = await sendMessage(page, prompt);
    expect(reply).toMatch(/AXDR/i);
    expect(reply).not.toMatch(/Possible interpretations/i);
    expect(reply).not.toMatch(/Missing opening 0x7E flag/i);

    await page.getByRole('button', { name: /Decode - AXDR/i }).last().click();
    const expandedText = await page.locator('[data-testid="chat-message-list"]').innerText();
    expect(expandedText).toMatch(/date-time/i);
  }
});

test('2.2b Ambiguous multi-payload input surfaces grounded candidates instead of guessing', async ({ page }) => {
  const reply = await sendMessage(page, 'Decode one of these frames: 7EA00A030383CD6F7E and 7EA00A0101934D7E');

  expect(reply).toMatch(/Possible interpretations/i);
  expect(reply).toMatch(/Recommended:/i);
  expect(reply).toMatch(/HDLC frame candidate/i);
  expect(reply).toMatch(/clarify which interpretation you want|more explicit payload/i);
});

test('2.2c AARE diagnostic prompts stay on the DLMS security/documentation path', async ({ page }) => {
  const reply = await sendMessage(page, 'AARE association rejected, diagnostic 6 - what does this usually mean?');

  expect(reply).toMatch(/diagnostic 6/i);
  expect(reply).toMatch(/association/i);
  expect(reply).not.toMatch(/SICONIA Analysis|alarm code/i);
  expect(reply).toMatch(/DLMS Standard/i);
});

// ── 2.3 ── HEX FRAME decode ─────────────────────────────────────────────────
test('2.3 HEX_FRAME — SNRM frame decoded with U_FRAME badge', async ({ page }) => {
  await page.locator('[data-testid="chat-input"]').fill('7EA00A030383CD6F7E');
  await page.locator('[data-testid="send-button"]').click();
  const responseText = await waitForResponse(page);

  // Input class badge should say HEX FRAME
  const userBubble = page.locator('[data-testid="chat-message-list"]')
    .locator('text=HEX FRAME')
    .first();
  await expect(userBubble).toBeVisible({ timeout: 5_000 });

  // Decode badge should show U_FRAME / SNRM
  await expect(
    page.locator('text=/U_FRAME|SNRM/').first(),
  ).toBeVisible({ timeout: 5_000 });

  // Response should not claim it's an unrecognised APDU
  expect(responseText).not.toMatch(/unrecognized apdu/i);
});

// ── 2.4 ── ALARM CODE ───────────────────────────────────────────────────────
test('2.4 ALARM_CODE — badge and SICONIA panel appear', async ({ page }) => {
  await page.locator('[data-testid="chat-input"]').fill('0x1342');
  await page.locator('[data-testid="send-button"]').click();
  await waitForResponse(page);

  // Input class badge
  await expect(
    page.locator('[data-testid="chat-message-list"]').locator('text=ALARM CODE').first(),
  ).toBeVisible({ timeout: 5_000 });

  // Severity badge — 0x1342 should decode to something non-trivial
  const hasSeverity = await page.locator('text=/HIGH|MEDIUM|CRITICAL/i').first().isVisible()
    .catch(() => false);
  expect(hasSeverity).toBe(true);
});

// ── 2.5 ── LOG BLOCK ────────────────────────────────────────────────────────
test('2.5 LOG_BLOCK — classified as LOG BLOCK with WAN layer', async ({ page }) => {
  // Use fill+Enter instead of sendMessage() to preserve the embedded newline correctly
  await page.locator('[data-testid="chat-input"]').fill(
    '2024-03-20 10:00:01 [WAN] ERROR: Connection timeout\n' +
    '2024-03-20 10:00:05 [WAN] WARN: Retry attempt 1/3',
  );
  await page.locator('[data-testid="send-button"]').click();
  await waitForResponse(page);

  // Input class badge
  await expect(
    page.locator('[data-testid="chat-message-list"]').locator('text=LOG BLOCK').first(),
  ).toBeVisible({ timeout: 5_000 });

  // WAN layer should be mentioned somewhere (badge or response)
  const messageList = await page.locator('[data-testid="chat-message-list"]').innerText();
  expect(messageList).toMatch(/WAN/);
});

// ── 2.6 ── XML TRACE ────────────────────────────────────────────────────────
test('2.6 XML_TRACE — classified and SICONIA panel rendered', async ({ page }) => {
  const xml = '<trace><event type="alarm" code="0x0001"/></trace>';
  await sendMessage(page, xml);

  await expect(
    page.locator('[data-testid="chat-message-list"]').locator('text=XML TRACE').first(),
  ).toBeVisible({ timeout: 5_000 });
});

// ── 2.7 ── Session continuity (STM) ─────────────────────────────────────────
test('2.7 Second message in same session references STM context', async ({ page }) => {
  // First message — decode an SNRM frame
  await sendMessage(page, '7EA00A030383CD6F7E');

  // Second message — ask about the previous input
  const followUp = await sendMessage(page, 'What type of frame was that?');

  // The response should reference the decoded frame
  const relevant =
    /SNRM/i.test(followUp) ||
    /U.?Frame/i.test(followUp) ||
    /connection/i.test(followUp) ||
    /HDLC/i.test(followUp);
  expect(relevant).toBe(true);
});

// ── 2.8a ── Wrapped HDLC frame normalization (new in this sprint) ────────────
test('2.8a Prose-wrapped HEX frame is normalized and decoded deterministically', async ({ page }) => {
  // The DLMS flexibility layer extracts the bare hex from prose wrappers
  await page.locator('[data-testid="chat-input"]').fill(
    'Decode this HDLC frame: 7EA00A030383CD6F7E',
  );
  await page.locator('[data-testid="send-button"]').click();
  const listText = await waitForResponse(page);

  // Decode result must still land on the deterministic decode path
  const decoded = /SNRM|U_FRAME|HDLC|CONNECT/i.test(listText);
  expect(decoded).toBe(true);

  // The wrapped frame must not be misrouted into SICONIA-style analysis.
  expect(listText).not.toMatch(/SICONIA Analysis|alarm code|XML trace/i);
});

// ── 2.8 ── MCP status badge ─────────────────────────────────────────────────
test('2.8 MCP status badge is visible in header', async ({ page }) => {
  // Badge labels: "MCP Online", "Local Fallback", "Checking MCP"
  const badge = page.locator('button[aria-label*="Refresh status"]');
  await expect(badge).toBeVisible({ timeout: 10_000 });

  const label = await badge.innerText();
  const validLabels = ['MCP Online', 'Local Fallback', 'Checking MCP'];
  expect(validLabels.some((l) => label.includes(l))).toBe(true);

  // Should NOT show a red error state (only green / muted / yellow is acceptable)
  await expect(page.locator('text=MCP Error')).not.toBeVisible();
});
