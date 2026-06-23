import { buildRequestArtifacts, buildSubmittedInput, dbMessagesToHistory } from './chatUtils';
import { Message } from '../hooks/useConversations';

describe('chatUtils', () => {
  it('builds submitted input from typed text only', () => {
    expect(buildSubmittedInput('  Explain OBIS  ', [])).toBe('Explain OBIS');
  });

  it('builds submitted input from attachment only', () => {
    expect(buildSubmittedInput('', [{
      filename: 'trace.log',
      text: 'LOG BLOCK',
      inputClass: 'log_block',
      type: 'text/plain',
      suggestedEndpoint: 'siconia',
    }])).toBe('LOG BLOCK');
  });

  it('combines attachment text with typed context', () => {
    expect(buildSubmittedInput('Need the root cause', [{
      filename: 'incident.log',
      text: '2024-03-20 [DLMS] ERROR',
      inputClass: 'log_block',
      type: 'text/plain',
      suggestedEndpoint: 'siconia',
    }])).toBe(
      '2024-03-20 [DLMS] ERROR\n\n[User context]\nNeed the root cause'
    );
  });

  it('preserves attachment order when multiple files are queued', () => {
    expect(buildSubmittedInput('Compare both traces', [
      {
        filename: 'a.log',
        text: 'FIRST LOG',
        inputClass: 'log_block',
        type: 'text/plain',
        suggestedEndpoint: 'siconia',
      },
      {
        filename: 'b.xml',
        text: '<xml>SECOND</xml>',
        inputClass: 'xml_trace',
        type: 'text/plain',
        suggestedEndpoint: 'siconia',
      },
    ])).toBe(
      '[Attachment 1: a.log]\nFIRST LOG\n\n[Attachment 2: b.xml]\n<xml>SECOND</xml>\n\n[User context]\nCompare both traces'
    );
  });

  it('builds request artifacts from queued attachments in order', () => {
    expect(buildRequestArtifacts([
      {
        filename: 'frame.hex',
        text: '  7EA00A030383CD6F7E  ',
        inputClass: 'hex_frame',
        type: 'text/plain',
        suggestedEndpoint: 'decode',
      },
      {
        filename: 'trace.xml',
        text: '\n<trace />\n',
        inputClass: 'xml_trace',
        type: 'text/plain',
        suggestedEndpoint: 'siconia',
      },
      {
        filename: 'empty.txt',
        text: '   ',
        inputClass: 'query',
        type: 'text/plain',
        suggestedEndpoint: 'decode',
      },
    ])).toEqual([
      {
        source: 'ATTACHMENT',
        filename: 'frame.hex',
        text: '7EA00A030383CD6F7E',
        hintedInputClass: 'hex_frame',
        suggestedEndpoint: 'decode',
      },
      {
        source: 'ATTACHMENT',
        filename: 'trace.xml',
        text: '<trace />',
        hintedInputClass: 'xml_trace',
        suggestedEndpoint: 'siconia',
      },
    ]);
  });

  it('restores decode and analysis payloads from persisted messages', () => {
    const messages: Message[] = [
      {
        id: 'decode-1',
        role: 'assistant',
        input_class: 'hex_frame',
        intent: 'FRAME_DECODE',
        raw_input: '7EA0',
        session_id: 'sess-decode',
        decode_result: {
          apduType: 'GET_RESPONSE',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'APDU_HEX',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
            extractorNote: 'Recovered APDU payload from wrapped prose input',
          },
        },
        explanation: 'Decoded frame',
        used_mcp_fallback: false,
        created_at: '2026-05-06T08:00:00.000Z',
        strategy_metadata: {
          selectedStrategy: 'DLMS_FRAME_DECODE',
          selectedLabel: 'HDLC frame decode',
          confidence: 0.92,
          ambiguous: false,
          tentative: false,
          candidates: [],
          warnings: [],
        },
        orchestration_mode: 'DETERMINISTIC_FAST_PATH',
        planner_used: false,
        tool_trace: [
          {
            toolName: 'decode_frame',
            summary: 'Decoded the HDLC control frame deterministically.',
            authoritative: true,
            provenance: 'JAVA',
          },
        ],
        planner_fallback_reason: 'Deterministic frame decode selected directly.',
      },
      {
        id: 'analysis-1',
        role: 'assistant',
        input_class: 'log_block',
        intent: 'SICONIA_TROUBLESHOOT',
        raw_input: 'LOG BLOCK',
        session_id: 'sess-analysis',
        decode_result: {
          inputClass: 'log_block',
          alarmResults: [
            {
              code: '0x1342',
              severity: 'HIGH',
              rootCause: 'Authentication mismatch',
              remediation: 'Verify credentials',
              affectedComponent: 'SECURITY',
            },
          ],
        },
        explanation: 'Security issue found',
        used_mcp_fallback: true,
        created_at: '2026-05-06T08:05:00.000Z',
        strategy_metadata: {
          selectedStrategy: 'SICONIA_LOG_ANALYSIS',
          selectedLabel: 'SICONIA log analysis',
          confidence: 0.87,
          ambiguous: false,
          tentative: true,
          candidates: [],
          warnings: ['Recovered dominant log block from wrapped prose input'],
        },
        orchestration_mode: 'STRUCTURED_PLUS_AGENTIC',
        planner_used: true,
        tool_trace: [
          {
            toolName: 'analyze_log',
            summary: 'Parsed the dominant log block and extracted issue categories.',
            authoritative: true,
            provenance: 'MCP',
          },
          {
            toolName: 'search_docs',
            summary: 'Retrieved supporting troubleshooting guidance.',
            authoritative: false,
            provenance: 'RAG',
          },
        ],
        planner_fallback_reason: 'Structured log analysis was enriched with grounded context.',
      },
    ];

    const history = dbMessagesToHistory(messages);

    expect(history).toHaveLength(2);
    expect(history[0].decodeResult).toEqual({
      apduType: 'GET_RESPONSE',
      gbtPartial: false,
      processingMetadata: {
        normalizedKind: 'APDU_HEX',
        provenance: 'STRUCTURED_HEURISTIC',
        warnings: [],
        extractorNote: 'Recovered APDU payload from wrapped prose input',
      },
    });
    expect(history[0].intent).toBe('FRAME_DECODE');
    expect(history[0].sessionId).toBe('sess-decode');
    expect(history[0].strategyMetadata).toEqual(messages[0].strategy_metadata);
    expect(history[0].orchestrationMode).toBe('DETERMINISTIC_FAST_PATH');
    expect(history[0].plannerUsed).toBe(false);
    expect(history[0].toolTrace).toEqual(messages[0].tool_trace);
    expect(history[0].plannerFallbackReason).toBe('Deterministic frame decode selected directly.');
    expect(history[0].siconiaAnalysis).toBeNull();
    expect(history[1].decodeResult).toBeNull();
    expect(history[1].siconiaAnalysis).toEqual({
      inputClass: 'log_block',
      alarmResults: [
        {
          code: '0x1342',
          severity: 'HIGH',
          rootCause: 'Authentication mismatch',
          remediation: 'Verify credentials',
          affectedComponent: 'SECURITY',
        },
      ],
    });
    expect(history[1].usedFallback).toBe(true);
    expect(history[1].intent).toBe('SICONIA_TROUBLESHOOT');
    expect(history[1].sessionId).toBe('sess-analysis');
    expect(history[1].strategyMetadata).toEqual(messages[1].strategy_metadata);
    expect(history[1].orchestrationMode).toBe('STRUCTURED_PLUS_AGENTIC');
    expect(history[1].plannerUsed).toBe(true);
    expect(history[1].toolTrace).toEqual(messages[1].tool_trace);
    expect(history[1].plannerFallbackReason).toBe('Structured log analysis was enriched with grounded context.');
  });

  it('restores XML-only SICONIA results with provenance metadata from persisted messages', () => {
    const messages: Message[] = [
      {
        id: 'analysis-xml',
        role: 'assistant',
        input_class: 'xml_trace',
        intent: 'SICONIA_TROUBLESHOOT',
        raw_input: '<Event />',
        session_id: 'sess-xml',
        decode_result: {
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
            rawXml: '<Event />',
          },
        },
        explanation: 'Structured XML recovered',
        used_mcp_fallback: false,
        created_at: '2026-05-06T08:10:00.000Z',
        strategy_metadata: {
          selectedStrategy: 'SICONIA_XML_ANALYSIS',
          selectedLabel: 'SICONIA XML analysis',
          confidence: 0.91,
          ambiguous: false,
          tentative: true,
          candidates: [],
          warnings: ['Recovered from wrapped prose'],
        },
        orchestration_mode: 'STRUCTURED_PLUS_AGENTIC',
        planner_used: true,
        tool_trace: [
          {
            toolName: 'analyze_xml',
            summary: 'Recovered embedded XML and derived alarm results.',
            authoritative: true,
            provenance: 'MCP',
          },
        ],
        planner_fallback_reason: 'Wrapped XML was normalized before explanation.',
      },
    ];

    const history = dbMessagesToHistory(messages);

    expect(history[0].siconiaAnalysis).toEqual(messages[0].decode_result);
    expect(history[0].decodeResult).toBeNull();
    expect(history[0].sessionId).toBe('sess-xml');
    expect(history[0].strategyMetadata).toEqual(messages[0].strategy_metadata);
    expect(history[0].explanation).toBe('Structured XML recovered');
    expect(history[0].orchestrationMode).toBe('STRUCTURED_PLUS_AGENTIC');
    expect(history[0].plannerUsed).toBe(true);
    expect(history[0].toolTrace).toEqual(messages[0].tool_trace);
    expect(history[0].plannerFallbackReason).toBe('Wrapped XML was normalized before explanation.');
  });

  it('restores multi-artifact assistant results from persisted messages', () => {
    const messages: Message[] = [
      {
        id: 'batch-1',
        role: 'assistant',
        input_class: 'query',
        intent: 'UNKNOWN',
        raw_input: 'Decode these',
        decode_result: null,
        explanation: 'Summary across artifacts',
        session_id: 'sess-batch',
        artifact_results: [
          {
            artifactId: 'artifact-1',
            index: 0,
            source: 'ATTACHMENT',
            filename: 'frame.hex',
            rawInput: '7EA00A030383CD6F7E',
            inputClass: 'HEX_FRAME',
            intent: 'FRAME_DECODE',
            explanation: 'Frame explanation',
          },
          {
            artifactId: 'artifact-2',
            index: 1,
            source: 'ATTACHMENT',
            filename: 'payload.hex',
            rawInput: 'C4020109060100010800FF',
            inputClass: 'QUERY',
            intent: 'APDU_ANALYSIS',
            explanation: 'APDU explanation',
          },
        ],
        used_mcp_fallback: false,
        created_at: '2026-05-06T09:00:00.000Z',
      },
    ];

    const history = dbMessagesToHistory(messages);

    expect(history).toHaveLength(1);
    expect(history[0].artifactResults).toHaveLength(2);
    expect(history[0].artifactResults?.[0]).toMatchObject({
      artifactId: 'artifact-1',
      filename: 'frame.hex',
      intent: 'FRAME_DECODE',
    });
    expect(history[0].artifactResults?.[1]).toMatchObject({
      artifactId: 'artifact-2',
      filename: 'payload.hex',
      intent: 'APDU_ANALYSIS',
    });
  });
});
