import { act, renderHook, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import { useConversations } from './useConversations';

function jsonResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

describe('useConversations', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    vi.restoreAllMocks();
    global.fetch = originalFetch;
  });

  it('sorts fetched conversations from newest to oldest', async () => {
    global.fetch = vi.fn().mockResolvedValue(
      jsonResponse([
        {
          id: 'newer',
          title: 'Newer',
          created_at: '2026-05-06T10:00:00.000Z',
          updated_at: '2026-05-06T10:00:00.000Z',
          message_count: 1,
        },
        {
          id: 'older',
          title: 'Older',
          created_at: '2026-05-05T10:00:00.000Z',
          updated_at: '2026-05-05T10:00:00.000Z',
          message_count: 2,
        },
      ])
    ) as typeof fetch;

    const { result } = renderHook(() => useConversations('jwt-token'));

    await waitFor(() => {
      expect(result.current.conversations).toHaveLength(2);
    });

    expect(result.current.conversations.map((item) => item.title)).toEqual([
      'Newer',
      'Older',
    ]);
  });

  it('keeps created conversations in newest-to-oldest order', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);

      if (url.endsWith('/conversations') && !init?.method) {
        return jsonResponse([
          {
            id: 'c-1',
            title: 'First',
            created_at: '2026-05-05T08:00:00.000Z',
            updated_at: '2026-05-05T08:00:00.000Z',
            message_count: 1,
          },
          {
            id: 'c-2',
            title: 'Second',
            created_at: '2026-05-06T08:00:00.000Z',
            updated_at: '2026-05-06T08:00:00.000Z',
            message_count: 1,
          },
        ]);
      }

      if (url.endsWith('/conversations') && init?.method === 'POST') {
        return jsonResponse({
          id: 'c-3',
          title: 'Newest',
          created_at: '2026-05-07T08:00:00.000Z',
          updated_at: '2026-05-07T08:00:00.000Z',
          message_count: 0,
        });
      }

      throw new Error(`Unhandled request: ${url}`);
    });

    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() => useConversations('jwt-token'));

    await waitFor(() => {
      expect(result.current.conversations).toHaveLength(2);
    });

    await act(async () => {
      await result.current.createConversation('Newest');
    });

    expect(result.current.conversations.map((item) => item.title)).toEqual([
      'Newest',
      'Second',
      'First',
    ]);
  });

  it('sends structured message payloads with keepalive and refreshes the conversation', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);

      if (url.endsWith('/conversations') && !init?.method) {
        return jsonResponse([
          {
            id: 'conv-1',
            title: 'Conversation 1',
            created_at: '2026-05-06T08:00:00.000Z',
            updated_at: '2026-05-06T08:00:00.000Z',
            message_count: 0,
          },
        ]);
      }

      if (url.endsWith('/conversations/conv-1/messages')) {
        return jsonResponse({ ok: true });
      }

      if (url.endsWith('/conversations/conv-1') && !init?.method) {
        return jsonResponse({
          id: 'conv-1',
          title: 'Conversation 1',
          created_at: '2026-05-06T08:00:00.000Z',
          updated_at: '2026-05-06T08:10:00.000Z',
          message_count: 1,
          messages: [],
        });
      }

      throw new Error(`Unhandled request: ${url}`);
    });

    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() => useConversations('jwt-token'));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });

    let saveResult = false;
    await act(async () => {
      saveResult = await result.current.saveMessage({
        convId: 'conv-1',
        role: 'assistant',
        rawInput: 'Explain HLS authentication',
        inputClass: 'query',
        intent: 'SECURITY_EXPLAIN',
        explanation: 'It is a challenge-response mechanism.',
        sessionId: 'sess-1',
        structuredResult: {
          apduType: 'GET_RESPONSE',
          gbtPartial: false,
        },
        strategyMetadata: {
          selectedStrategy: 'DLMS_APDU_ANALYSIS',
          selectedLabel: 'APDU analysis',
          confidence: 0.94,
          ambiguous: false,
          tentative: false,
          candidates: [],
          warnings: [],
        },
        usedFallback: true,
        explanationMode: 'GROUNDED_LLM',
        toolProvenance: 'MIXED',
        orchestrationMode: 'STRUCTURED_PLUS_AGENTIC',
        plannerUsed: true,
        toolTrace: [
          {
            toolName: 'decode_apdu',
            summary: 'Decoded GET_RESPONSE payload',
            authoritative: true,
            provenance: 'JAVA',
          },
        ],
        plannerFallbackReason: 'Structured decode was enriched with grounded context.',
      });
    });

    expect(saveResult).toBe(true);

    const saveCall = fetchMock.mock.calls.find((call) => {
      const [url, init] = call;
      return String(url).endsWith('/conversations/conv-1/messages') && init?.method === 'POST';
    });
    expect(saveCall).toBeDefined();
    expect(saveCall?.[1]?.keepalive).toBe(true);

    const payload = JSON.parse(String(saveCall?.[1]?.body));
    expect(payload).toMatchObject({
      role: 'assistant',
      rawInput: 'Explain HLS authentication',
      inputClass: 'query',
      intent: 'SECURITY_EXPLAIN',
      explanation: 'It is a challenge-response mechanism.',
      sessionId: 'sess-1',
      usedMcpFallback: true,
      strategyMetadataJson: JSON.stringify({
        selectedStrategy: 'DLMS_APDU_ANALYSIS',
        selectedLabel: 'APDU analysis',
        confidence: 0.94,
        ambiguous: false,
        tentative: false,
        candidates: [],
        warnings: [],
      }),
      explanationMode: 'GROUNDED_LLM',
      toolProvenance: 'MIXED',
      orchestrationMode: 'STRUCTURED_PLUS_AGENTIC',
      plannerUsed: true,
      toolTraceJson: JSON.stringify([
        {
          toolName: 'decode_apdu',
          summary: 'Decoded GET_RESPONSE payload',
          authoritative: true,
          provenance: 'JAVA',
        },
      ]),
      plannerFallbackReason: 'Structured decode was enriched with grounded context.',
    });
    expect(JSON.parse(payload.decodeResultJson)).toEqual({
      apduType: 'GET_RESPONSE',
      gbtPartial: false,
    });
    expect(result.current.conversations[0]).toMatchObject({
      id: 'conv-1',
      message_count: 1,
    });

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/conversations/conv-1',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer jwt-token',
          }),
        })
      );
    });
  });
});
