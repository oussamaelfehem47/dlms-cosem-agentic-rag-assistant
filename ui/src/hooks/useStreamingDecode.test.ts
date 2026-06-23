import { act, renderHook, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import { useStreamingDecode } from './useStreamingDecode';

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });

  return { promise, resolve, reject };
}

function createStreamResponse(chunks: string[]): Response {
  let index = 0;
  const encoder = new TextEncoder();

  return {
    ok: true,
    status: 200,
    body: {
      getReader() {
        return {
          async read() {
            if (index >= chunks.length) {
              return { done: true, value: undefined };
            }

            const value = encoder.encode(chunks[index]);
            index += 1;
            return { done: false, value };
          },
        };
      },
    },
  } as unknown as Response;
}

describe('useStreamingDecode', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    global.fetch = originalFetch;
  });

  it('waits for assistant message persistence before submit resolves', async () => {
    const saveDeferred = createDeferred<boolean>();
    const saveMessage = vi.fn(() => saveDeferred.promise);

    global.fetch = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: decode\ndata: {"sessionId":"sess-1","intent":"FRAME_DECODE","decodeResult":{"apduType":"GET_RESPONSE","gbtPartial":false},"usedFallback":true,"explanationMode":"GROUNDED_LLM","toolProvenance":"MIXED","orchestrationMode":"STRUCTURED_PLUS_AGENTIC","plannerUsed":true,"toolTrace":[{"toolName":"decode_apdu","summary":"Decoded GET_RESPONSE payload","authoritative":true,"provenance":"JAVA"},{"toolName":"search_docs","summary":"Retrieved DLMS protocol context","authoritative":false,"provenance":"RAG"}],"plannerFallbackReason":"Structured decode was enriched with documentation context.","strategyMetadata":{"selectedStrategy":"DLMS_APDU_ANALYSIS","selectedLabel":"APDU analysis","confidence":0.93,"ambiguous":false,"tentative":false,"candidates":[],"warnings":[]}}\n\n',
        'event: token\ndata: {"t":"Decoded explanation"}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    ) as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token', undefined, 'user-1', saveMessage)
    );

    let submitPromise!: Promise<boolean>;
    let settled = false;

    act(() => {
      submitPromise = result.current.submit('Explain HLS authentication', undefined, 'conv-1');
      void submitPromise.then(() => {
        settled = true;
      });
    });

    await waitFor(() => {
      expect(saveMessage).toHaveBeenCalledTimes(1);
    });

    expect(result.current.isStreaming).toBe(true);
    expect(settled).toBe(false);

    await act(async () => {
      saveDeferred.resolve(true);
      await submitPromise;
    });

    expect(settled).toBe(true);
    expect(result.current.history).toHaveLength(1);
    expect(result.current.history[0]).toMatchObject({
      explanation: 'Decoded explanation',
      usedFallback: true,
      sessionId: 'sess-1',
      intent: 'FRAME_DECODE',
      explanationMode: 'GROUNDED_LLM',
      toolProvenance: 'MIXED',
      orchestrationMode: 'STRUCTURED_PLUS_AGENTIC',
      plannerUsed: true,
      plannerFallbackReason: 'Structured decode was enriched with documentation context.',
      strategyMetadata: expect.objectContaining({
        selectedStrategy: 'DLMS_APDU_ANALYSIS',
        selectedLabel: 'APDU analysis',
      }),
      toolTrace: [
        expect.objectContaining({
          toolName: 'decode_apdu',
          authoritative: true,
        }),
        expect.objectContaining({
          toolName: 'search_docs',
          authoritative: false,
        }),
      ],
    });
    expect(saveMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        intent: 'FRAME_DECODE',
        explanationMode: 'GROUNDED_LLM',
        toolProvenance: 'MIXED',
        orchestrationMode: 'STRUCTURED_PLUS_AGENTIC',
        plannerUsed: true,
        plannerFallbackReason: 'Structured decode was enriched with documentation context.',
        strategyMetadata: expect.objectContaining({
          selectedStrategy: 'DLMS_APDU_ANALYSIS',
        }),
        toolTrace: expect.arrayContaining([
          expect.objectContaining({ toolName: 'decode_apdu' }),
          expect.objectContaining({ toolName: 'search_docs' }),
        ]),
      })
    );
  });

  it('persists structured results when the stream closes without an explicit done event', async () => {
    const saveMessage = vi.fn().mockResolvedValue(true);

    global.fetch = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: decode\ndata: {"sessionId":"sess-2","intent":"FRAME_DECODE","decodeResult":{"apduType":"GET_RESPONSE","gbtPartial":false}}\n\n',
      ])
    ) as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token', undefined, 'user-1', saveMessage)
    );

    await act(async () => {
      await result.current.submit('Explain security suite 1', undefined, 'conv-2');
    });

    expect(result.current.history).toHaveLength(1);
    expect(result.current.history[0].decodeResult).toEqual({
      apduType: 'GET_RESPONSE',
      gbtPartial: false,
    });
    expect(result.current.history[0].explanation).toBe('');
    expect(saveMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        convId: 'conv-2',
        intent: 'FRAME_DECODE',
        explanation: '',
        structuredResult: {
          apduType: 'GET_RESPONSE',
          gbtPartial: false,
        },
      })
    );
  });

  it('captures intent from analysis events for SICONIA-routed documentation queries', async () => {
    const saveMessage = vi.fn().mockResolvedValue(true);

    global.fetch = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: analysis\ndata: {"sessionId":"sess-3","intent":"DOCUMENTATION"}\n\n',
        'event: token\ndata: {"t":"Local operations summary"}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    ) as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token', undefined, 'user-1', saveMessage)
    );

    await act(async () => {
      await result.current.submit('What is Local operations in SICONIA?', undefined, 'conv-3');
    });

    expect(result.current.lastIntent).toBe('DOCUMENTATION');
    expect(result.current.history[0]).toMatchObject({
      intent: 'DOCUMENTATION',
      explanation: 'Local operations summary',
    });
    expect(saveMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        convId: 'conv-3',
        intent: 'DOCUMENTATION',
      })
    );
  });

  it('submits attachment artifacts separately and persists artifact results from the stream', async () => {
    const saveMessage = vi.fn().mockResolvedValue(true);
    const fetchMock = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: decode\ndata: {"sessionId":"sess-batch","intent":"UNKNOWN","artifactResults":[{"artifactId":"artifact-1","index":0,"source":"ATTACHMENT","filename":"frame.hex","rawInput":"7EA00A030383CD6F7E","inputClass":"HEX_FRAME","intent":"FRAME_DECODE","explanation":"Frame explanation"},{"artifactId":"artifact-2","index":1,"source":"ATTACHMENT","filename":"payload.hex","rawInput":"C4020109060100010800FF","inputClass":"QUERY","intent":"APDU_ANALYSIS","explanation":"APDU explanation"}],"orchestrationMode":"STRUCTURED_PLUS_AGENTIC","plannerUsed":true}\n\n',
        'event: token\ndata: {"t":"Summary across artifacts"}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    );
    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token', undefined, 'user-1', saveMessage)
    );

    await act(async () => {
      await result.current.submit(
        'Compare these',
        undefined,
        'conv-batch',
        undefined,
        [
          {
            source: 'ATTACHMENT',
            filename: 'frame.hex',
            text: '7EA00A030383CD6F7E',
            hintedInputClass: 'hex_frame',
            suggestedEndpoint: 'decode',
          },
          {
            source: 'ATTACHMENT',
            filename: 'payload.hex',
            text: 'C4020109060100010800FF',
            hintedInputClass: 'query',
            suggestedEndpoint: 'decode',
          },
        ]
      );
    });

    const [, requestInit] = fetchMock.mock.calls[0];
    const requestBody = JSON.parse(String((requestInit as RequestInit).body));

    expect(requestBody.artifacts).toEqual([
      expect.objectContaining({
        source: 'ATTACHMENT',
        filename: 'frame.hex',
        text: '7EA00A030383CD6F7E',
        hintedInputClass: 'hex_frame',
      }),
      expect.objectContaining({
        source: 'ATTACHMENT',
        filename: 'payload.hex',
        text: 'C4020109060100010800FF',
        hintedInputClass: 'query',
      }),
    ]);
    expect(result.current.history).toHaveLength(1);
    expect(result.current.history[0].artifactResults).toHaveLength(2);
    expect(result.current.history[0].artifactResults?.[0]).toMatchObject({
      artifactId: 'artifact-1',
      filename: 'frame.hex',
      intent: 'FRAME_DECODE',
    });
    expect(result.current.history[0].artifactResults?.[1]).toMatchObject({
      artifactId: 'artifact-2',
      filename: 'payload.hex',
      intent: 'APDU_ANALYSIS',
    });
    expect(saveMessage).toHaveBeenCalledWith(
      expect.objectContaining({
        convId: 'conv-batch',
        explanation: 'Summary across artifacts',
        artifactResults: expect.arrayContaining([
          expect.objectContaining({ artifactId: 'artifact-1' }),
          expect.objectContaining({ artifactId: 'artifact-2' }),
        ]),
      })
    );
  });

  it('accepts metadata-wrapped artifact results in decode events', async () => {
    const saveMessage = vi.fn().mockResolvedValue(true);
    const fetchMock = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: decode\ndata: {"sessionId":"sess-meta","metadata":{"artifactResults":[{"artifactId":"artifact-1","index":0,"source":"PASTED_BLOCK","rawInput":"7EA00A030383CD6F7E","inputClass":"HEX_FRAME","intent":"FRAME_DECODE","explanation":"Frame explanation"}],"orchestrationMode":"STRUCTURED_PLUS_AGENTIC","plannerUsed":true}}\n\n',
        'event: token\ndata: {"t":"Summary across artifacts"}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    );
    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token', undefined, 'user-1', saveMessage)
    );

    await act(async () => {
      await result.current.submit('7EA00A030383CD6F7E\n\n03 01', undefined, 'conv-meta');
    });

    expect(result.current.history).toHaveLength(1);
    expect(result.current.history[0].artifactResults).toHaveLength(1);
    expect(result.current.history[0].artifactResults?.[0]).toMatchObject({
      artifactId: 'artifact-1',
      intent: 'FRAME_DECODE',
    });
    expect(result.current.history[0].orchestrationMode).toBe('STRUCTURED_PLUS_AGENTIC');
    expect(result.current.history[0].plannerUsed).toBe(true);
  });

  it('keeps SICONIA documentation queries on the decode/documentation stream instead of forcing the SICONIA endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: decode\ndata: {"sessionId":"sess-4","intent":"DOCUMENTATION"}\n\n',
        'event: token\ndata: {"t":"Local operations summary"}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    );
    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token')
    );

    await act(async () => {
      await result.current.submit('What is Local operations in SICONIA?');
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/chat/stream',
      expect.any(Object)
    );
    expect(result.current.lastIntent).toBe('DOCUMENTATION');
  });

  it('detects embedded XML, wrapped alarm prose, and wrapped log prose as SICONIA input families', () => {
    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token')
    );

    expect(result.current.detectInputClass('Please inspect this trace <Event timestamp="2024-01-15T10:30:00Z"><Alarm code="0x1342"/></Event>'))
      .toBe('xml_trace');
    expect(result.current.detectInputClass('critical 0x1342 on concentrator DCU-01'))
      .toBe('alarm_code');
    expect(result.current.detectInputClass('Please review these logs:\n2024-01-15 10:30:00 [WAN] ERROR Connection timeout\n2024-01-15 10:30:01 [WAN] WARN Retrying'))
      .toBe('log_block');
  });

  it('does not classify DLMS security and protocol acronyms as SICONIA alarm codes', () => {
    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token')
    );

    expect(result.current.detectInputClass('AARE association rejected, diagnostic 6 - what does this usually mean?'))
      .toBe('query');
    expect(result.current.detectInputClass('Explain HLS authentication in DLMS/COSEM and when it should be used.'))
      .toBe('query');
    expect(result.current.detectInputClass('How does GMAC protect DLMS traffic?'))
      .toBe('query');
    expect(result.current.detectInputClass('What is AARQ in DLMS?'))
      .toBe('query');
  });

  it('keeps DLMS security prompts on the decode stream instead of forcing the SICONIA endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: decode\ndata: {"sessionId":"sess-sec","intent":"SECURITY_EXPLAIN"}\n\n',
        'event: token\ndata: {"t":"Diagnostic 6 usually indicates the association request was rejected by the server."}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    );
    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token')
    );

    await act(async () => {
      await result.current.submit('AARE association rejected, diagnostic 6 - what does this usually mean?');
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/chat/stream',
      expect.any(Object)
    );
    expect(result.current.lastIntent).toBe('SECURITY_EXPLAIN');
  });

  it('treats wrapped single-line timestamped entries as a log block', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      createStreamResponse([
        'event: analysis\ndata: {"sessionId":"sess-log","intent":"SICONIA_TROUBLESHOOT"}\n\n',
        'event: token\ndata: {"t":"WAN log summary"}\n\n',
        'event: done\ndata: {}\n\n',
      ])
    );
    global.fetch = fetchMock as typeof fetch;

    const { result } = renderHook(() =>
      useStreamingDecode(null, 'jwt-token')
    );

    const wrappedLog = 'Please analyze this log: 2024-01-15 10:30:00 [WAN] ERROR Connection timeout to DCU-01 2024-01-15 10:30:05 [WAN] WARN Retry scheduled';
    expect(result.current.detectInputClass(wrappedLog)).toBe('log_block');

    await act(async () => {
      await result.current.submit(wrappedLog);
    });

    expect(fetchMock).toHaveBeenCalledWith('/api/chat/stream', expect.any(Object));
    const [, requestInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    const requestBody = JSON.parse(String(requestInit.body));
    expect(requestBody).toMatchObject({
      inputClass: 'QUERY',
      rawInput: 'Please analyze this log:\n2024-01-15 10:30:00 [WAN] ERROR Connection timeout to DCU-01\n2024-01-15 10:30:05 [WAN] WARN Retry scheduled',
    });
  });
});
