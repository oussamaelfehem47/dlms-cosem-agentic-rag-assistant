interface ConversationMessageRecord {
  id: string;
  role: string;
  input_class: string | null;
  intent?: string | null;
  raw_input: string | null;
  decode_result: unknown;
  strategy_metadata?: unknown;
  explanation: string | null;
  used_mcp_fallback: boolean;
  created_at: string;
}

interface ConversationRecord {
  id: string;
  title: string;
  created_at: string;
  updated_at: string;
  messages: ConversationMessageRecord[];
}

interface SseEvent {
  event: string;
  data: unknown;
}

function createJwtToken(): string {
  const toBase64Url = (value: string) =>
    Cypress.Buffer.from(value)
      .toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/g, '');

  const header = toBase64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = toBase64Url(JSON.stringify({
    sub: 'user-1',
    exp: Math.floor(Date.now() / 1000) + 3600,
  }));

  return `${header}.${payload}.signature`;
}

function getUserActiveConversationKey(userId: string) {
  return `dlms_user_${userId}_active_conversation_id`;
}

function getUserActiveHistoryKey(userId: string) {
  return `dlms_user_${userId}_active_conversation_history`;
}

function seedAuthenticatedSession(
  win: Window,
  options?: {
    activeConversationId?: string;
    activeHistory?: unknown;
  }
) {
  const userId = 'user-1';
  const token = createJwtToken();
  win.localStorage.setItem('dlms_access_token', token);
  win.localStorage.setItem('dlms_refresh_token', 'refresh-token');
  win.localStorage.setItem('dlms_user', JSON.stringify({
    id: userId,
    username: 'tester',
    email: 'tester@example.com',
    role: 'ADMIN',
  }));

  if (options?.activeConversationId) {
    win.localStorage.setItem(getUserActiveConversationKey(userId), options.activeConversationId);
  }

  if (options?.activeHistory) {
    win.localStorage.setItem(
      getUserActiveHistoryKey(userId),
      JSON.stringify(options.activeHistory)
    );
  }
}

function buildSseResponse(events: SseEvent[]): string {
  return events
    .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n`)
    .join('\n');
}

function installChatApiMocks(
  seededConversations: ConversationRecord[] = [],
  options?: {
    delayedConversationId?: string;
    delayMs?: number;
    decodeStreamResponder?: (body: Record<string, unknown>) => string;
    siconiaStreamResponder?: (body: Record<string, unknown>) => string;
  }
) {
  const conversations = seededConversations.map((conversation) => ({
    ...conversation,
    messages: conversation.messages.map((message) => ({ ...message })),
  }));

  let conversationCounter = conversations.length + 1;
  let messageCounter = 1;
  const shouldUseSiconiaResponder = (body: Record<string, unknown>) => {
    const rawInput = String(body.rawInput ?? '');
    if (!options?.siconiaStreamResponder) {
      return false;
    }
    if (!options.decodeStreamResponder) {
      return true;
    }
    return (
      /<\w/i.test(rawInput)
      || /xml trace/i.test(rawInput)
      || (/\b0x[0-9a-f]{1,8}\b/i.test(rawInput) && /\balarm\b/i.test(rawInput))
      || /\d{4}[-/]\d{2}[-/]\d{2}.*\[(WAN|PLC|RF|HES|DLMS)\]/i.test(rawInput)
    );
  };

  const toConversationListItem = (conversation: ConversationRecord) => ({
    id: conversation.id,
    title: conversation.title,
    created_at: conversation.created_at,
    updated_at: conversation.updated_at,
    message_count: conversation.messages.length,
  });

  cy.intercept('GET', '/api/mcp/health', {
    statusCode: 200,
    body: { reachable: true },
  });

  cy.intercept('GET', '/api/conversations', (req) => {
    req.reply({
      statusCode: 200,
      body: conversations.map(toConversationListItem),
    });
  });

  cy.intercept('POST', '/api/conversations', (req) => {
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    const createdAt = new Date(Date.UTC(2026, 4, 6, 9, conversationCounter, 0)).toISOString();
    const conversation: ConversationRecord = {
      id: `conv-${conversationCounter}`,
      title: body.title,
      created_at: createdAt,
      updated_at: createdAt,
      messages: [],
    };
    conversationCounter += 1;
    conversations.push(conversation);

    req.reply({
      statusCode: 200,
      body: toConversationListItem(conversation),
    });
  });

  cy.intercept('GET', /\/api\/conversations\/[^/]+$/, (req) => {
    const conversationId = req.url.split('/').pop() as string;
    const conversation = conversations.find((item) => item.id === conversationId);

    if (!conversation) {
      req.reply({ statusCode: 404, body: { error: 'Not found' } });
      return;
    }

    req.reply({
      delay: options?.delayedConversationId === conversationId ? options.delayMs ?? 300 : 0,
      statusCode: 200,
      body: {
        ...toConversationListItem(conversation),
        messages: conversation.messages,
      },
    });
  });

  cy.intercept('POST', /\/api\/conversations\/[^/]+\/messages$/, (req) => {
    const parts = req.url.split('/');
    const conversationId = parts[parts.length - 2];
    const conversation = conversations.find((item) => item.id === conversationId);
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;

    if (!conversation) {
      req.reply({ statusCode: 404, body: { error: 'Not found' } });
      return;
    }

    const createdAt = new Date(Date.UTC(2026, 4, 6, 10, 0, messageCounter)).toISOString();
    conversation.updated_at = createdAt;
    conversation.messages.push({
      id: `msg-${messageCounter}`,
      role: body.role,
      input_class: body.inputClass,
      intent: body.intent ?? null,
      raw_input: body.rawInput,
      decode_result: body.decodeResultJson ? JSON.parse(body.decodeResultJson) : null,
      explanation: body.explanation,
      used_mcp_fallback: body.usedMcpFallback,
      created_at: createdAt,
    });
    messageCounter += 1;

    req.reply({ statusCode: 200, body: { ok: true } });
  });

  cy.intercept('PATCH', /\/api\/conversations\/[^/]+\/title$/, (req) => {
    const parts = req.url.split('/');
    const conversationId = parts[parts.length - 2];
    const conversation = conversations.find((item) => item.id === conversationId);
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;

    if (!conversation) {
      req.reply({ statusCode: 404, body: { error: 'Not found' } });
      return;
    }

    conversation.title = body.title;
    conversation.updated_at = new Date(Date.UTC(2026, 4, 6, 11, conversationCounter, 0)).toISOString();

    req.reply({
      statusCode: 200,
      body: toConversationListItem(conversation),
    });
  });

  cy.intercept('DELETE', /\/api\/conversations\/[^/]+$/, (req) => {
    const conversationId = req.url.split('/').pop() as string;
    const index = conversations.findIndex((item) => item.id === conversationId);

    if (index >= 0) {
      conversations.splice(index, 1);
    }

    req.reply({ statusCode: 200, body: { ok: true } });
  });

  cy.intercept('POST', '/api/chat/stream', (req) => {
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    const useSiconiaResponder = shouldUseSiconiaResponder(body);
    req.reply({
      statusCode: 200,
      headers: {
        'content-type': 'text/event-stream',
      },
      body: useSiconiaResponder
        ? options?.siconiaStreamResponder?.(body) ?? buildSseResponse([
            {
              event: 'analysis',
              data: {
                sessionId: 'sess-siconia',
                intent: 'SICONIA_TROUBLESHOOT',
                siconiaResult: {
                  inputClass: 'XML_TRACE',
                  processingMetadata: {
                    normalizedInputClass: 'XML_TRACE',
                    provenance: 'STRUCTURED_DIRECT',
                    warnings: [],
                  },
                },
              },
            },
            {
              event: 'token',
              data: { t: `Generated SICONIA answer for ${body.rawInput}` },
            },
            {
              event: 'done',
              data: {},
            },
          ])
        : options?.decodeStreamResponder?.(body) ?? buildSseResponse([
            {
              event: 'decode',
              data: {
                sessionId: 'sess-1',
                decodeResult: {
                  apduType: 'GET_RESPONSE',
                  gbtPartial: false,
                },
              },
            },
            {
              event: 'token',
              data: { t: `Generated answer for ${body.rawInput}` },
            },
            {
              event: 'done',
              data: {},
            },
          ]),
    });
  });
}

describe('Chat flows', () => {
  it('shows the login screen for signed-out users', () => {
    cy.visit('/');
    cy.contains('Welcome back');
    cy.contains('Sign In');
  });

  it('creates a conversation, saves the reply, and restores it after refresh', () => {
    installChatApiMocks();

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-empty-state"]').should('be.visible');
    cy.get('[data-testid="chat-input"]').type('Explain HLS authentication');
    cy.get('[data-testid="send-button"]').click();

    cy.get('[data-testid="chat-message-list"]')
      .contains('Generated answer for Explain HLS authentication')
      .should('exist');
    cy.reload();

    cy.get('[data-testid="chat-empty-state"]').should('not.exist');
    cy.get('[data-testid="chat-message-list"]')
      .contains('Generated answer for Explain HLS authentication')
      .should('exist');

    cy.window().then((win) => {
      expect(win.localStorage.getItem(getUserActiveConversationKey('user-1'))).to.match(/^conv-/);
    });
  });

  it('surfaces security quick actions and sample prompts without changing routing locally', () => {
    installChatApiMocks();

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.contains('button', 'Explain HLS').click();
    cy.get('[data-testid="chat-input"]')
      .should('have.value', 'Explain HLS authentication in DLMS/COSEM and when it is used.');

    cy.get('[data-testid="samples-toggle"]').click();
    cy.contains('button', 'Security').click({ force: true });
    cy.contains('button', 'AARE Reject').click();

    cy.get('[data-testid="chat-input"]')
      .should('have.value', 'AARE association rejected, diagnostic 6 - what does this usually mean?');
  });

  it('keeps the sidebar ordered from newest to oldest and deletes the active conversation cleanly', () => {
    installChatApiMocks([
      {
        id: 'conv-older',
        title: 'Older investigation',
        created_at: '2026-05-04T08:00:00.000Z',
        updated_at: '2026-05-04T08:00:00.000Z',
        messages: [
          {
            id: 'msg-older',
            role: 'assistant',
            input_class: 'query',
            raw_input: 'Older',
            decode_result: null,
            explanation: 'Older explanation',
            used_mcp_fallback: false,
            created_at: '2026-05-04T08:00:00.000Z',
          },
        ],
      },
      {
        id: 'conv-recent',
        title: 'Recent investigation',
        created_at: '2026-05-06T08:00:00.000Z',
        updated_at: '2026-05-06T08:00:00.000Z',
        messages: [
          {
            id: 'msg-recent',
            role: 'assistant',
            input_class: 'query',
            raw_input: 'Recent',
            decode_result: null,
            explanation: 'Recent explanation',
            used_mcp_fallback: false,
            created_at: '2026-05-06T08:00:00.000Z',
          },
        ],
      },
    ]);

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[aria-label="Open conversation sidebar"]').click();

    cy.get('[data-testid="conversation-sidebar"]').within(() => {
      cy.get('[data-testid="conversation-item"]').then(($items) => {
        const titles = [...$items].map((item) => item.textContent || '');
        expect(titles[0]).to.contain('Recent investigation');
        expect(titles[1]).to.contain('Older investigation');
      });
    });

    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Recent investigation')
      .click({ force: true });
    cy.get('[data-testid="chat-message-list"]').contains('Recent explanation').should('exist');

    cy.get('[aria-label="Open conversation sidebar"]').click();
    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Recent investigation')
      .should('have.attr', 'data-active', 'true')
      .within(() => {
        cy.get('[aria-label="Delete conversation"]').should('be.visible').click({ force: true });
      });

    cy.get('[data-testid="chat-empty-state"]').should('exist');
    cy.window().then((win) => {
      expect(win.localStorage.getItem(getUserActiveConversationKey('user-1'))).to.equal(null);
      expect(win.localStorage.getItem(getUserActiveHistoryKey('user-1'))).to.equal(null);
    });
  });

  it('persists pinned conversations across refresh using local storage metadata', () => {
    installChatApiMocks([
      {
        id: 'conv-security',
        title: 'Security investigation',
        created_at: '2026-05-04T08:00:00.000Z',
        updated_at: '2026-05-04T08:00:00.000Z',
        messages: [],
      },
      {
        id: 'conv-decode',
        title: 'Decode investigation',
        created_at: '2026-05-05T08:00:00.000Z',
        updated_at: '2026-05-05T08:00:00.000Z',
        messages: [],
      },
    ]);

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[aria-label="Open conversation sidebar"]').click();
    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Security investigation')
      .click({ force: true });

    cy.get('[aria-label="Open conversation sidebar"]').click();
    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Security investigation')
      .should('have.attr', 'data-active', 'true')
      .within(() => {
        cy.get('[aria-label="Pin conversation"]').should('be.visible').click({ force: true });
    });

    cy.reload();
    cy.get('[aria-label="Open conversation sidebar"]').click({ force: true });
    cy.get('[data-testid="conversation-sidebar"]').should('exist');
    cy.get('[data-testid="conversation-sidebar"]').contains('button', 'Pinned').click({ force: true });
    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Security investigation')
      .should('exist');
    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Decode investigation')
      .should('not.exist');
  });

  it('restores a new-thread draft after refresh', () => {
    installChatApiMocks();

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('Need help with frame counter replay protection');
    cy.reload();

    cy.get('[data-testid="chat-input"]').should('have.value', 'Need help with frame counter replay protection');
  });

  it('shows a restore state instead of the empty state while reopening the last conversation', () => {
    installChatApiMocks(
      [
        {
          id: 'conv-restore',
          title: 'Restored conversation',
          created_at: '2026-05-06T07:30:00.000Z',
          updated_at: '2026-05-06T07:35:00.000Z',
          messages: [
            {
              id: 'msg-restore',
              role: 'assistant',
              input_class: 'query',
              raw_input: 'Restore this',
              decode_result: null,
              explanation: 'Restored explanation',
              used_mcp_fallback: false,
              created_at: '2026-05-06T07:35:00.000Z',
            },
          ],
        },
      ],
      {
        delayedConversationId: 'conv-restore',
        delayMs: 400,
      }
    );

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win, {
          activeConversationId: 'conv-restore',
        });
      },
    });

    cy.get('[data-testid="chat-restoring-state"]').should('be.visible');
    cy.get('[data-testid="chat-empty-state"]').should('not.exist');
    cy.contains('Restored explanation').should('be.visible');
  });

  it('renders casual greetings without a sources block', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-hello',
            intent: 'UNKNOWN',
          },
        },
        {
          event: 'token',
          data: { t: body.rawInput === 'hello' ? 'Hello.' : `Generated answer for ${body.rawInput}` },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('hello');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('Hello.').should('be.visible');
    cy.get('[data-testid="assistant-sources"]').should('not.exist');
    cy.contains('Sources').should('not.exist');
  });

  it('renders OBIS source footers as structured source citations', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-obis',
            intent: body.rawInput === 'What is OBIS 1.0.1.8.0.255?' ? 'OBIS_LOOKUP' : 'DOCUMENTATION',
            decodeResult: {
              apduType: 'UNKNOWN',
              gbtPartial: false,
              processingMetadata: {
                normalizedKind: 'OBIS_QUERY',
                provenance: 'STRUCTURED_HEURISTIC',
                warnings: [],
                extractorNote: 'Recovered OBIS code from wrapped prose input',
              },
              obisResolutions: [
                {
                  obis: '1.0.1.8.0.255',
                  description: 'Active energy import total',
                  ic: 3,
                  unit: 'kWh',
                  scaler: -3,
                  tierUsed: 'KG',
                },
              ],
            },
          },
        },
        {
          event: 'token',
          data: {
            t: body.rawInput === 'What is OBIS 1.0.1.8.0.255?'
              ? 'OBIS 1.0.1.8.0.255 represents Active energy import total (IC 3).\n\nSources: OBIS Resolver (KG)'
              : `Generated answer for ${body.rawInput}`,
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('What is OBIS 1.0.1.8.0.255?');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('OBIS 1.0.1.8.0.255 represents Active energy import total (IC 3).').should('be.visible');
    cy.get('[data-testid="assistant-sources"]').should('be.visible');
    cy.get('[data-testid="assistant-source-item"]').should('contain.text', 'OBIS Resolver (KG)');
    cy.get('[data-testid="assistant-dlms-provenance"]').should('contain.text', 'Heuristic');
    cy.contains('Sources: OBIS Resolver (KG)').should('not.exist');
  });

  it('keeps AARE diagnostic prompts on the DLMS security path with a non-empty reply', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-aare',
            intent: body.rawInput === 'AARE association rejected, diagnostic 6 - what does this usually mean?'
              ? 'SECURITY_EXPLAIN'
              : 'DOCUMENTATION',
          },
        },
        {
          event: 'token',
          data: {
            t: body.rawInput === 'AARE association rejected, diagnostic 6 - what does this usually mean?'
              ? 'Diagnostic 6 usually means the server rejected the association because the proposed authentication or application context did not match.\n\nSources: DLMS Standard — §Security architecture'
              : `Generated answer for ${body.rawInput}`,
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('AARE association rejected, diagnostic 6 - what does this usually mean?');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('Diagnostic 6 usually means the server rejected the association because the proposed authentication or application context did not match.').should('be.visible');
    cy.get('[data-testid="assistant-category-badge"]').should('contain.text', 'security');
    cy.get('[data-testid="assistant-source-item"]').should('contain.text', 'DLMS Standard');
    cy.contains('SICONIA Analysis').should('not.exist');
  });

  it('renders explicit short AXDR payloads as deterministic AXDR decodes without an APDU section', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-axdr-short',
            intent: 'FRAME_DECODE',
            decodeResult: {
              apduType: 'UNKNOWN',
              gbtPartial: false,
              rawHex: body.rawInput === 'Explain AXDR payload 0301' ? '0301' : null,
              frameLength: 2,
              axdrTree: {
                type: 'boolean',
                value: true,
              },
              processingMetadata: {
                normalizedKind: 'AXDR_HEX',
                provenance: 'STRUCTURED_HEURISTIC',
                warnings: [],
                extractorNote: 'Recovered AXDR payload from wrapped prose input',
              },
            },
          },
        },
        {
          event: 'token',
          data: {
            t: 'What happened: The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.\nCan I trust it: Yes. Only the decoded AXDR tree is being summarized.\nNext step: Use the structured panel to inspect the AXDR hierarchy and recovered values.',
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('Explain AXDR payload 0301');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('Decode - AXDR').should('be.visible');
    cy.get('[data-testid="assistant-dlms-provenance"]').should('contain.text', 'Heuristic');
    cy.contains('What happened: The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.').should('be.visible');
    cy.contains('APDU Type').should('not.exist');
  });

  it('renders documentation answers without a decode badge when backend intent is documentation', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-docs',
            intent: 'DOCUMENTATION',
          },
        },
        {
          event: 'token',
          data: {
            t: body.rawInput === 'What is the DLMS Green Book?'
              ? 'The DLMS Green Book defines the architecture and communication rules for DLMS/COSEM systems.\n\nSources: DLMS Standard — §2 Referenced documents; DLMS Standard — §General'
              : `Generated answer for ${body.rawInput}`,
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('What is the DLMS Green Book?');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('The DLMS Green Book defines the architecture and communication rules for DLMS/COSEM systems.').should('be.visible');
    cy.get('[data-testid="assistant-category-badge"]').should('not.exist');
    cy.get('[data-testid="assistant-source-item"]').first().should('contain.text', 'DLMS Standard');
  });

  it('auto-renames courtesy-first conversations after the first strong technical prompt and updates sidebar counts', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => {
        if (body.rawInput === 'thanks') {
          return buildSseResponse([
            {
              event: 'decode',
              data: {
                sessionId: 'sess-thanks',
                intent: 'UNKNOWN',
              },
            },
            {
              event: 'token',
              data: {
                t: "You're welcome. I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarms, XML traces, and communication logs.",
              },
            },
            {
              event: 'done',
              data: {},
            },
          ]);
        }

        return buildSseResponse([
          {
            event: 'decode',
            data: {
              sessionId: 'sess-ansible',
              intent: 'DOCUMENTATION',
            },
          },
          {
            event: 'token',
            data: {
              t: 'Ansible Inventory defines host configurations for playbooks.\n\nSources: Confluence — 1. Ansible Inventory (SICCICD)',
            },
          },
          {
            event: 'done',
            data: {},
          },
        ]);
      },
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('thanks');
    cy.get('[data-testid="send-button"]').click();
    cy.contains("You're welcome. I can help with DLMS/COSEM questions").should('be.visible');

    cy.get('[data-testid="chat-input"]').type('What is Ansible Inventory?');
    cy.get('[data-testid="send-button"]').click();
    cy.contains('Ansible Inventory defines host configurations for playbooks.').should('be.visible');

    cy.get('[aria-label="Open conversation sidebar"]').click();
    cy.get('[data-testid="conversation-sidebar"]')
      .contains('[data-testid="conversation-item"]', 'Ansible Inventory')
      .should('contain.text', '2 messages')
      .and('not.contain.text', 'thanks');
  });

  it('renders wrapped frame decode answers without speculative wording', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-frame-wrap',
            intent: 'FRAME_DECODE',
            decodeResult: {
              hdlcFrame: {
                frameType: 'U_FRAME',
                uFrameType: 'SNRM',
                fcsValid: true,
              },
              apduType: 'UNKNOWN',
              gbtPartial: false,
              processingMetadata: {
                normalizedKind: 'FRAME_HEX',
                provenance: 'STRUCTURED_HEURISTIC',
                warnings: [],
                extractorNote: 'Recovered embedded HDLC frame from wrapped prose input',
              },
              rawHex: body.rawInput === 'Decode this HDLC frame: 7EA00A030383CD6F7E'
                ? '7EA00A030383CD6F7E'
                : null,
            },
          },
        },
        {
          event: 'token',
          data: {
            t: 'What happened: The deterministic parser decoded a valid HDLC control frame.\nCan I trust it: Trust the control-frame classification and raw frame metadata.\nNext step: Use the structured decode to inspect the frame type and addresses.',
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('Decode this HDLC frame: 7EA00A030383CD6F7E');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('What happened: The deterministic parser decoded a valid HDLC control frame.').should('be.visible');
    cy.get('[data-testid="assistant-dlms-provenance"]').should('contain.text', 'Heuristic');
    cy.get('[data-testid="chat-message-list"]').should('not.contain.text', 'assuming the first byte');
  });

  it('marks invalid-FCS frame decodes as tentative instead of fully structured', () => {
    installChatApiMocks([], {
      decodeStreamResponder: () => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-fcs',
            intent: 'FRAME_DECODE',
            decodeResult: {
              hdlcFrame: {
                frameType: 'S_FRAME',
                sFrameType: 'UNKNOWN',
                clientSap: 1,
                serverSap: 35651712,
                fcsValid: false,
              },
              apduType: 'UNKNOWN',
              rawHex: '7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E',
              frameLength: 41,
              parseErrors: ['Checksum failed'],
              processingMetadata: {
                normalizedKind: 'FRAME_HEX',
                provenance: 'STRUCTURED_DIRECT',
                warnings: ['FCS invalid'],
              },
            },
          },
        },
        {
          event: 'token',
          data: {
            t: 'What happened: The FCS check failed for this S_FRAME. The bytes were received, but the integrity check does not match.\nCan I trust it: Only treat the outer HDLC header as tentative.\nWhat to do next: Re-capture or retransmit the same frame before trusting the decode.',
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E');
    cy.get('[data-testid="send-button"]').click();

    cy.get('[data-testid="assistant-dlms-provenance"]').should('contain.text', 'Tentative');
    cy.contains('What happened: The FCS check failed for this S_FRAME').should('be.visible');
  });

  it('renders nested XML traces with a heuristic badge and structured event details', () => {
    installChatApiMocks([], {
      siconiaStreamResponder: () => buildSseResponse([
        {
          event: 'analysis',
          data: {
            sessionId: 'sess-xml',
            intent: 'SICONIA_TROUBLESHOOT',
            siconiaResult: {
              inputClass: 'XML_TRACE',
              processingMetadata: {
                normalizedInputClass: 'XML_TRACE',
                provenance: 'STRUCTURED_HEURISTIC',
                warnings: [],
                extractorNote: 'Recovered embedded XML from wrapped prose input',
              },
              xmlTrace: {
                events: [
                  {
                    type: 'ALARM',
                    code: '0x1342',
                    timestamp: '2024-01-15T10:30:00Z',
                    deviceId: 'DCU-01',
                    errorCode: 'critical',
                  },
                ],
                parseErrors: [],
                rawXml: '<Event timestamp="2024-01-15T10:30:00Z"><Alarm code="0x1342" severity="critical"/><Source device="DCU-01"/></Event>',
              },
            },
          },
        },
        {
          event: 'token',
          data: {
            t: 'What it means: Critical alarm 0x1342 was recovered from the XML trace.\nImpact: Device DCU-01 reported a critical event.\nNext step: Review the structured XML event details below.',
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('Please inspect this XML trace: <Event timestamp="2024-01-15T10:30:00Z"><Alarm code="0x1342" severity="critical"/><Source device="DCU-01"/></Event>');
    cy.get('[data-testid="send-button"]').click();

    cy.get('[data-testid="assistant-siconia-provenance"]').should('contain.text', 'Heuristic');
    cy.contains('Critical alarm 0x1342 was recovered from the XML trace.').should('be.visible');
    cy.contains('button', 'SICONIA').click({ force: true });
    cy.contains('Recovered embedded XML from wrapped prose input').should('exist');
    cy.contains('0x1342').should('exist');
    cy.contains('DCU-01').should('exist');
    cy.contains('XML was detected, but the structure did not match the supported event schema.').should('not.exist');
  });

  it('shows only one deterministic structured explanation block for SICONIA XML results', () => {
    installChatApiMocks([], {
      siconiaStreamResponder: () => buildSseResponse([
        {
          event: 'analysis',
          data: {
            sessionId: 'sess-siconia-dedup',
            intent: 'SICONIA_TROUBLESHOOT',
            siconiaResult: {
              inputClass: 'XML_TRACE',
              processingMetadata: {
                normalizedInputClass: 'XML_TRACE',
                provenance: 'STRUCTURED_DIRECT',
                warnings: [],
              },
              alarmMatches: [
                {
                  code: '0x1342',
                  severity: 'HIGH',
                  source: 'HES',
                  rootCause: 'SICONIA DCU comm failure',
                  remediation: 'Check DCU-HES link, verify credentials',
                },
              ],
            },
          },
        },
        {
          event: 'token',
          data: {
            t: 'What it means: Alarm 0x1342 is HIGH on HES.\nImpact: SICONIA DCU comm failure.\nNext step: Check DCU-HES link, verify credentials.',
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('<Event timestamp="2024-01-15T10:30:00Z"><Alarm code="0x1342" severity="critical"/><Source device="DCU-01"/></Event>');
    cy.get('[data-testid="send-button"]').click();

    cy.contains('What it means: Alarm 0x1342 is HIGH on HES.').should('be.visible');
    cy.contains('Interpretation').should('not.exist');
  });

  it('converges bare and wrapped AXDR prompts on the same answer-first deterministic decode', () => {
    installChatApiMocks([], {
      decodeStreamResponder: (body) => {
        const rawInput = String(body.rawInput ?? '');
        const direct = rawInput === '1907E80416010E1E0000003C00';
        return buildSseResponse([
          {
            event: 'decode',
            data: {
              sessionId: direct ? 'sess-axdr-direct' : 'sess-axdr-wrapped',
              intent: 'FRAME_DECODE',
              strategyMetadata: {
                selectedStrategy: 'DLMS_AXDR_DECODE',
                selectedLabel: 'AXDR Decode',
                confidence: direct ? 0.99 : 0.92,
                ambiguous: false,
                tentative: false,
                candidates: [],
                warnings: [],
              },
              decodeResult: {
                apduType: 'UNKNOWN',
                gbtPartial: false,
                rawHex: '1907E80416010E1E0000003C00',
                frameLength: 13,
                axdrTree: {
                  type: 'date-time',
                  tag: '0x19',
                  value: '2024-04-22T14:30:00',
                },
                processingMetadata: {
                  normalizedKind: 'AXDR_HEX',
                  provenance: direct ? 'STRUCTURED_DIRECT' : 'STRUCTURED_HEURISTIC',
                  warnings: [],
                  extractorNote: direct ? null : 'Recovered AXDR payload from wrapped prose input',
                },
              },
            },
          },
          {
            event: 'token',
            data: {
              t: 'What happened: The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.\nCan I trust it: Yes. Only the decoded AXDR tree and recovered identifiers are being summarized.\nNext step: Use the structured panel to inspect the AXDR hierarchy and any recovered OBIS or object identifiers.',
            },
          },
          {
            event: 'done',
            data: {},
          },
        ]);
      },
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('1907E80416010E1E0000003C00');
    cy.get('[data-testid="send-button"]').click();
    cy.contains('Decode - AXDR').should('be.visible');
    cy.get('[data-testid="chat-message-list"]').should('contain.text', 'What happened: The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.');
    cy.get('[data-testid="assistant-dlms-provenance"]').should('contain.text', 'Structured');

    cy.get('[data-testid="chat-input"]').type('payload 1907E80416010E1E0000003C00');
    cy.get('[data-testid="send-button"]').click();
    cy.contains('Decode - AXDR').should('exist');
    cy.get('[data-testid="chat-message-list"]').should('contain.text', 'What happened: The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.');
    cy.get('[data-testid="assistant-dlms-provenance"]').last().should('contain.text', 'Heuristic');
    cy.get('[data-testid="chat-message-list"]').should('not.contain.text', 'Possible interpretations');
  });

  it('renders ranked ambiguity candidates from strategy metadata instead of guessing a single decode', () => {
    installChatApiMocks([], {
      decodeStreamResponder: () => buildSseResponse([
        {
          event: 'decode',
          data: {
            sessionId: 'sess-ambiguous',
            intent: 'UNKNOWN',
            strategyMetadata: {
              selectedStrategy: 'HDLC_FRAME_CANDIDATE_1',
              selectedLabel: 'HDLC frame candidate 1',
              confidence: 0.78,
              ambiguous: true,
              tentative: true,
              warnings: ['Multiple plausible HDLC payloads detected in the request'],
              candidates: [
                {
                  strategy: 'HDLC_FRAME_CANDIDATE_1',
                  label: 'HDLC frame candidate 1',
                  confidence: 0.78,
                  rationale: 'Recovered a plausible HDLC frame candidate from the request.',
                  deterministic: true,
                  tentative: true,
                  warnings: [],
                },
                {
                  strategy: 'HDLC_FRAME_CANDIDATE_2',
                  label: 'HDLC frame candidate 2',
                  confidence: 0.77,
                  rationale: 'Recovered a plausible HDLC frame candidate from the request.',
                  deterministic: true,
                  tentative: true,
                  warnings: [],
                },
                {
                  strategy: 'UNKNOWN',
                  label: 'Unknown',
                  confidence: 0.1,
                  rationale: 'No dominant deterministic or semantic strategy was found.',
                  deterministic: false,
                  tentative: true,
                  warnings: [],
                },
              ],
            },
          },
        },
        {
          event: 'token',
          data: {
            t: 'What happened: I found more than one plausible interpretation for this input.\nBest candidates:\n1. HDLC frame candidate 1 (confidence 78%) — Recovered a plausible HDLC frame candidate from the request.\n2. HDLC frame candidate 2 (confidence 77%) — Recovered a plausible HDLC frame candidate from the request.\n3. Unknown (confidence 10%) — No dominant deterministic or semantic strategy was found.\nCan I trust it: Not yet. The assistant is surfacing grounded candidates instead of guessing a single decode path.\nNext step: Clarify which interpretation you want, or resend a more explicit payload so the deterministic parser can choose one route confidently.',
          },
        },
        {
          event: 'done',
          data: {},
        },
      ]),
    });

    cy.visit('/', {
      onBeforeLoad(win) {
        seedAuthenticatedSession(win);
      },
    });

    cy.get('[data-testid="chat-input"]').type('Decode one of these frames: 7EA00A030383CD6F7E and 7EA00A0101934D7E');
    cy.get('[data-testid="send-button"]').click();

    cy.get('[data-testid="chat-message-list"]').should('contain.text', 'Possible interpretations');
    cy.get('[data-testid="chat-message-list"]').should('contain.text', 'Recommended: HDLC frame candidate 1');
    cy.get('[data-testid="chat-message-list"]').should('contain.text', 'HDLC frame candidate 2');
    cy.get('[data-testid="chat-message-list"]').should('contain.text', 'Unknown');
    cy.get('[data-testid="chat-message-list"]').should('not.contain.text', 'The deterministic parser decoded a valid HDLC control frame');
  });
});
