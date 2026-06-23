import {
  buildConversationExport,
  categoryFromIntent,
  cleanConversationTitleSource,
  detectConversationCategory,
  detectHistoryCategory,
  isWeakConversationTitle,
  resolveConversationCategory,
  isSecurityPrompt,
  resolveSuggestedEndpoint,
  suggestAutoRenameTitle,
  suggestConversationTitle,
} from './chatFeatureUtils';
import { ConversationEntry } from '../types';
import { UploadResult } from '../components/UploadButton';

const securityAttachment: UploadResult = {
  filename: 'security.log',
  text: 'AARQ rejected - authentication failure',
  inputClass: 'log_block',
  type: 'text/plain',
  suggestedEndpoint: 'siconia',
};

describe('chatFeatureUtils', () => {
  it('detects security-oriented prompts from keywords', () => {
    expect(isSecurityPrompt('Explain HLS authentication and GMAC')).toBe(true);
    expect(isSecurityPrompt('Decode this GET_RESPONSE frame')).toBe(false);
  });

  it('categorizes prompts using text and input class heuristics', () => {
    expect(detectConversationCategory('What is replay protection in DLMS?')).toBe('security');
    expect(detectConversationCategory('7EA00A030383CD6F7E', 'hex_frame')).toBe('decode');
    expect(detectConversationCategory('2024-03-20 [DLMS] ERROR: AARQ rejected', 'log_block')).toBe('security');
    expect(detectConversationCategory('<Event><Alarm>0x1342</Alarm></Event>', 'xml_trace')).toBe('incident');
  });

  it('prefers persisted intent over query heuristics for assistant category rendering', () => {
    expect(categoryFromIntent('DOCUMENTATION')).toBe('general');
    expect(categoryFromIntent('FRAME_DECODE')).toBe('decode');
    expect(resolveConversationCategory('Explain HDLC frame structure', 'query', 'DOCUMENTATION')).toBe('general');
    expect(resolveConversationCategory('Explain HDLC frame structure', 'query')).toBe('decode');
  });

  it('derives a history category from the latest user turn', () => {
    const history: ConversationEntry[] = [
      {
        id: 'entry-1',
        timestamp: new Date('2026-05-06T08:00:00.000Z'),
        inputClass: 'query',
        userInput: 'Explain OBIS 1.0.1.8.0.255',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'OBIS explanation',
        sessionId: 'sess-1',
        usedFallback: false,
      },
      {
        id: 'entry-2',
        timestamp: new Date('2026-05-06T08:05:00.000Z'),
        inputClass: 'query',
        userInput: 'How does frame counter replay protection work?',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'Security explanation',
        sessionId: 'sess-2',
        usedFallback: false,
      },
    ];

    expect(detectHistoryCategory(history)).toBe('security');
  });

  it('suggests concise conversation titles from local hints', () => {
    expect(
      suggestConversationTitle('Need help with HLS authentication failure', [securityAttachment], 'security')
    ).toBe('Need help with HLS authentication failure');

    expect(
      suggestConversationTitle('7EA00A030383CD6F7E', [], 'decode')
    ).toBe('7EA00A030383CD6F7E');

    expect(
      suggestConversationTitle('Explain replay protection and the role of the frame counter in DLMS security.', [], 'security')
    ).toBe('Replay protection and the role of the frame counter in DLMS security.');
  });

  it('cleans boilerplate and treats weak openers as untitled', () => {
    expect(
      cleanConversationTitleSource('Analyze this incident and suggest the likely root cause: 0x1342 on DCU-01')
    ).toBe('0x1342 on DCU-01');
    expect(suggestConversationTitle('hello', [], 'general')).toBe('');
    expect(isWeakConversationTitle('Hello')).toBe(true);
    expect(isWeakConversationTitle('Thanks')).toBe(true);
    expect(isWeakConversationTitle('Thank you for your help')).toBe(true);
    expect(isWeakConversationTitle('Okay')).toBe(true);
    expect(isWeakConversationTitle('0x1342')).toBe(true);
    expect(isWeakConversationTitle('Replay protection and frame counter in DLMS')).toBe(false);
  });

  it('auto-renames only once when a later turn is clearly better', () => {
    expect(
      suggestAutoRenameTitle(
        'New Conversation',
        'Explain replay protection and the role of the frame counter in DLMS security.',
        [],
        'security',
      )
    ).toBe('Replay protection and the role of the frame counter in DLMS security.');

    expect(
      suggestAutoRenameTitle(
        '0x1342',
        'Analyze this incident and suggest the likely root cause: DCU communication failure with retry loop',
        [],
        'incident',
      )
    ).toBe('DCU communication failure with retry loop');

    expect(
      suggestAutoRenameTitle(
        'Replay protection and frame counter in DLMS',
        'Explain HLS authentication failure',
        [],
        'security',
      )
    ).toBeNull();

    expect(
      suggestAutoRenameTitle(
        'New Conversation',
        'Explain HLS authentication failure',
        [],
        'security',
        { manualTitle: true }
      )
    ).toBeNull();

    expect(
      suggestAutoRenameTitle(
        'New Conversation',
        'Explain HLS authentication failure',
        [],
        'security',
        { autoRetitled: true }
      )
    ).toBeNull();
  });

  it('resolves suggested endpoints only when queued attachments agree', () => {
    expect(resolveSuggestedEndpoint([
      securityAttachment,
      {
        ...securityAttachment,
        filename: 'retry.log',
      },
    ])).toBe('siconia');

    expect(resolveSuggestedEndpoint([
      securityAttachment,
      {
        filename: 'frame.txt',
        text: '7EA00A030383CD6F7E',
        inputClass: 'hex_frame',
        type: 'text/plain',
        suggestedEndpoint: 'decode',
      },
    ])).toBeUndefined();
  });

  it('exports visible conversation content in markdown and text formats', () => {
    const history: ConversationEntry[] = [
      {
        id: 'entry-1',
        timestamp: new Date('2026-05-06T08:00:00.000Z'),
        inputClass: 'log_block',
        userInput: '2024-03-20 [DLMS] ERROR: AARQ rejected',
        decodeResult: null,
        siconiaAnalysis: {
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
        explanation: 'Authentication failure detected',
        sessionId: 'sess-3',
        usedFallback: false,
      },
    ];

    const markdown = buildConversationExport('Security Incident', history, 'markdown');
    const text = buildConversationExport('Security Incident', history, 'text');

    expect(markdown).toContain('# Security Incident');
    expect(markdown).toContain('### Structured Result');
    expect(markdown).toContain('Authentication failure detected');

    expect(text).toContain('Security Incident');
    expect(text).toContain('SICONIA analysis: 1 alarm result(s)');
    expect(text).toContain('"affectedComponent": "SECURITY"');
  });

  it('normalizes exported explanations and uses strategy-aware decode summaries', () => {
    const history: ConversationEntry[] = [
      {
        id: 'entry-axdr',
        timestamp: new Date('2026-06-01T08:00:00.000Z'),
        inputClass: 'query',
        userInput: '03 01',
        decodeResult: {
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'AXDR_HEX',
            provenance: 'STRUCTURED_DIRECT',
            warnings: [],
          },
          axdrTree: {
            type: 'boolean',
            tag: '0x03',
            value: true,
          },
          rawHex: '0301',
        },
        siconiaAnalysis: null,
        explanation: 'Line one.\\nLine two.',
        sessionId: 'sess-axdr',
        usedFallback: false,
      },
    ];

    const markdown = buildConversationExport('AXDR export', history, 'markdown');
    const text = buildConversationExport('AXDR export', history, 'text');

    expect(markdown).toContain('Line one.\nLine two.');
    expect(text).toContain('Line one.\nLine two.');
    expect(markdown).toContain('Decode result: AXDR Decode');
    expect(text).toContain('Decode result: AXDR Decode');
  });

  it('exports trailing source footers in a dedicated sources section', () => {
    const history: ConversationEntry[] = [
      {
        id: 'entry-doc',
        timestamp: new Date('2026-06-03T10:00:00.000Z'),
        inputClass: 'query',
        userInput: 'What is the DLMS Green Book?',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'The DLMS Green Book defines the DLMS/COSEM architecture.\n\nSources: DLMS Standard ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Ãƒâ€šÃ‚§GreenBook; DLMS Standard ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Ãƒâ€šÃ‚§General',
        sessionId: 'sess-doc',
        usedFallback: false,
      },
    ];

    const markdown = buildConversationExport('Docs export', history, 'markdown');
    const text = buildConversationExport('Docs export', history, 'text');

    expect(markdown).toContain('### Sources');
    expect(markdown).toContain('- DLMS Standard — §GreenBook');
    expect(markdown).not.toContain('architecture.\n\nSources:');

    expect(text).toContain('Sources');
    expect(text).toContain('- DLMS Standard — §GreenBook');
    expect(text).not.toContain('architecture.\n\nSources:');
  });

  it('repairs glued source footers before export', () => {
    const history: ConversationEntry[] = [
      {
        id: 'entry-glued',
        timestamp: new Date('2026-06-03T10:05:00.000Z'),
        inputClass: 'query',
        userInput: 'What is replay protection?',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'Replay protection rejects stale counters.Sources: DLMS Standard ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Ãƒâ€šÃ‚§Security Header',
        sessionId: 'sess-glued',
        usedFallback: false,
      },
    ];

    const markdown = buildConversationExport('Docs export', history, 'markdown');
    const text = buildConversationExport('Docs export', history, 'text');

    expect(markdown).toContain('Replay protection rejects stale counters.');
    expect(markdown).toContain('### Sources');
    expect(markdown).toContain('- DLMS Standard — §Security Header');
    expect(markdown).not.toContain('counters.Sources:');

    expect(text).toContain('Replay protection rejects stale counters.');
    expect(text).toContain('Sources');
    expect(text).toContain('- DLMS Standard — §Security Header');
    expect(text).not.toContain('counters.Sources:');
  });

  it('normalizes mojibake in exported conversation text', () => {
    const history: ConversationEntry[] = [
      {
        id: 'entry-symbols',
        timestamp: new Date('2026-06-03T10:10:00.000Z'),
        inputClass: 'query',
        userInput: 'Explain this decode',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'Status âœ… Â· scaler Ã—10^-3 â€” see Â§GreenBook',
        sessionId: 'sess-symbols',
        usedFallback: false,
      },
    ];

    const markdown = buildConversationExport('Symbols export', history, 'markdown');
    const text = buildConversationExport('Symbols export', history, 'text');

    expect(markdown).toContain('Status ✅ · scaler ×10^-3 — see §GreenBook');
    expect(markdown).not.toContain('âœ…');
    expect(text).toContain('Status ✅ · scaler ×10^-3 — see §GreenBook');
    expect(text).not.toContain('Â§');
  });
});

