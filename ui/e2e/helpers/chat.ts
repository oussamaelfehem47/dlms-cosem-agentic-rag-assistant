import { Locator, Page } from '@playwright/test';

const RESPONSE_TIMEOUT = 90_000; // 90 s — lfm2.5-thinking can be slow

/**
 * Type text into the composer and click Send (or press Enter).
 * Does NOT wait for the response.
 */
export async function typeMessage(page: Page, text: string): Promise<void> {
  const input = page.locator('[data-testid="chat-input"]');
  await input.click();
  await input.fill(text);
}

/**
 * Send a message and wait until streaming is complete.
 * Returns the full text content of the message list after the response.
 */
export async function sendMessage(page: Page, text: string): Promise<string> {
  const streamRequest = page.waitForRequest((request) =>
    request.method() === 'POST'
    && /\/api\/chat\/stream$/.test(new URL(request.url()).pathname)
  );

  await typeMessage(page, text);

  // Press Enter to submit (most reliable in an Ionic textarea)
  await page.locator('[data-testid="chat-input"]').press('Enter');
  await streamRequest;

  return waitForResponse(page);
}

/**
 * Wait until the LLM streaming is done:
 *  1. The chat-input textarea is re-enabled (isStreaming → false)
 *  2. The "Generating answer..." indicator is gone
 * Returns the full message-list text.
 */
export async function waitForResponse(page: Page, timeout = RESPONSE_TIMEOUT): Promise<string> {
  const input = page.locator('[data-testid="chat-input"]');

  // Ensure the composer actually entered the streaming state at least once
  // before we wait for completion, otherwise an immediate enabled check can
  // race the state transition and let tests continue while SSE is still active.
  await input.waitFor({ state: 'visible', timeout: 5_000 });
  await expectEventuallyDisabled(page, input, 5_000);

  // Wait for the textarea to become enabled again (streaming done)
  await page.waitForFunction(
    () => {
      const el = document.querySelector<HTMLTextAreaElement>('[data-testid="chat-input"]');
      return el !== null && !el.disabled;
    },
    { timeout },
  );

  // Also ensure "Generating answer..." has left the DOM
  await page
    .locator('text=Generating answer...')
    .waitFor({ state: 'detached', timeout: 5_000 })
    .catch(() => { /* might already be gone */ });

  return page.locator('[data-testid="chat-message-list"]').innerText();
}

async function expectEventuallyDisabled(page: Page, input: Locator, timeout: number): Promise<void> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await input.isDisabled().catch(() => false)) {
      return;
    }
    await page.waitForTimeout(100);
  }
  throw new Error('Chat input never entered the streaming/disabled state');
}

/**
 * Open the conversation sidebar.
 */
export async function openSidebar(page: Page): Promise<void> {
  const sidebar = page.locator('[data-testid="conversation-sidebar"]');
  const isOpen = await sidebar.evaluate((el) =>
    (el as HTMLElement).style.transform === 'translateX(0px)' ||
    getComputedStyle(el as HTMLElement).transform === 'none',
  ).catch(() => false);

  if (!isOpen) {
    await page.getByRole('button', { name: /open conversation sidebar/i }).click();
    await page.locator('[data-testid="conversation-sidebar"]').waitFor({ state: 'visible' });
  }
}

/**
 * Click "New conversation" in the sidebar.
 */
export async function startNewConversation(page: Page): Promise<void> {
  await openSidebar(page);
  await page.locator('[data-testid="new-conversation-button"]').click();
}
