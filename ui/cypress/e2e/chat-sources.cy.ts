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

function seedAuthenticatedSession(win: Window) {
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
  win.localStorage.removeItem(getUserActiveConversationKey(userId));
  win.localStorage.removeItem(getUserActiveHistoryKey(userId));
}

function buildSseResponse(events: SseEvent[]): string {
  return events
    .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n`)
    .join('\n');
}

function installChatApiMocks(decodeStreamResponder: (body: Record<string, unknown>) => string) {
  cy.intercept('GET', '/api/mcp/health', {
    statusCode: 200,
    body: { reachable: true },
  });

  cy.intercept('GET', '/api/conversations', {
    statusCode: 200,
    body: [],
  });

  cy.intercept('POST', '/api/conversations', {
    statusCode: 200,
    body: {
      id: 'conv-1',
      title: 'New Conversation',
      created_at: '2026-05-06T09:00:00.000Z',
      updated_at: '2026-05-06T09:00:00.000Z',
      message_count: 0,
    },
  });

  cy.intercept('GET', '/api/conversations/conv-1', {
    statusCode: 200,
    body: {
      id: 'conv-1',
      title: 'New Conversation',
      created_at: '2026-05-06T09:00:00.000Z',
      updated_at: '2026-05-06T09:00:00.000Z',
      messages: [],
    },
  });

  cy.intercept('POST', '/api/conversations/conv-1/messages', {
    statusCode: 200,
    body: { ok: true },
  });

  cy.intercept('POST', '/api/admin/reflection/feedback', {
    statusCode: 200,
    body: { ok: true },
  });

  cy.intercept('POST', '/api/chat/stream', (req) => {
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    req.reply({
      statusCode: 200,
      headers: {
        'content-type': 'text/event-stream',
      },
      body: decodeStreamResponder(body),
    });
  });
}

describe('Chat source rendering', () => {
  it('renders casual greetings without a sources block', () => {
    installChatApiMocks((body) => buildSseResponse([
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
    ]));

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
    installChatApiMocks((body) => buildSseResponse([
      {
        event: 'decode',
        data: {
          sessionId: 'sess-obis',
          intent: body.rawInput === 'What is OBIS 1.0.1.8.0.255?' ? 'OBIS_LOOKUP' : 'DOCUMENTATION',
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
    ]));

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
    cy.contains('Sources: OBIS Resolver (KG)').should('not.exist');
  });
});
