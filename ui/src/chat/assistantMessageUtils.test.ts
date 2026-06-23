import {
  compactGreetingReply,
  getDecodeFailureSummary,
  getDecodeInterpretationSummary,
  getSiconiaInterpretationSummary,
  hasInvalidFcs,
  normalizeTechnicalReply,
  stripRedundantStructuredExplanation,
  stripVerboseChecksumExplanation,
} from './assistantMessageUtils';
import { JavaDecodeResult, JavaSiconiaResult } from '../types';

const invalidFcsDecode: JavaDecodeResult = {
  hdlcFrame: {
    frameType: 'S_FRAME',
    sFrameType: 'RR',
    clientSap: 1,
    serverSap: 35651712,
    fcsValid: false,
  },
  apduType: 'UNKNOWN',
  gbtPartial: false,
  rawHex: '7EA0210002002303F17B2B80C401C100',
  parseErrors: ['Unexpected information field on supervisory frame', 'FCS invalid'],
};

const decodeSuccess: JavaDecodeResult = {
  hdlcFrame: {
    frameType: 'I_FRAME',
    clientSap: 16,
    serverSap: 1,
    fcsValid: true,
  },
  apduType: 'GET_RESPONSE',
  gbtPartial: false,
  obisResolutions: [{ obis: '1.0.1.8.0.255', description: 'Active energy import', ic: 3, unit: 'kWh', scaler: -3, tierUsed: 'RAG' }],
};

const obisLookupDecode: JavaDecodeResult = {
  apduType: 'UNKNOWN',
  gbtPartial: false,
  processingMetadata: {
    normalizedKind: 'OBIS_QUERY',
    provenance: 'STRUCTURED_HEURISTIC',
    warnings: [],
    extractorNote: 'Recovered OBIS code from wrapped prose input',
  },
  obisResolutions: [{ obis: '1.0.1.8.0.255', description: 'Active energy import total', ic: 3, unit: 'kWh', scaler: -3, tierUsed: 'KG' }],
};

const axdrDirectDecode: JavaDecodeResult = {
  apduType: 'UNKNOWN',
  gbtPartial: false,
  processingMetadata: {
    normalizedKind: 'AXDR_HEX',
    provenance: 'STRUCTURED_DIRECT',
    warnings: [],
  },
  axdrTree: {
    type: 'structure',
    tag: '0x02',
    children: [],
  },
};

const alarmAnalysis: JavaSiconiaResult = {
  inputClass: 'ALARM_CODE',
  alarmResults: [
    {
      code: '0x1342',
      severity: 'HIGH',
      rootCause: 'SICONIA DCU comm failure',
      remediation: 'Check DCU-HES link, verify credentials',
      affectedComponent: 'HES',
    },
  ],
};

describe('assistantMessageUtils', () => {
  it('detects invalid checksum decode results', () => {
    expect(hasInvalidFcs(invalidFcsDecode)).toBe(true);
    expect(hasInvalidFcs({
      ...invalidFcsDecode,
      hdlcFrame: {
        ...invalidFcsDecode.hdlcFrame!,
        fcsValid: true,
      },
      parseErrors: [],
    })).toBe(false);
  });

  it('builds a compact decode failure summary for invalid FCS frames', () => {
    const summary = getDecodeFailureSummary(invalidFcsDecode);

    expect(summary).not.toBeNull();
    expect(summary?.title).toBe('Checksum failed');
    expect(summary?.whatHappened).toContain('S_FRAME (RR)');
    expect(summary?.whatHappened).toContain('unexpected information field on supervisory frame');
    expect(summary?.canTrustIt).toContain('higher-layer payload interpretation');
    expect(summary?.nextSteps).toHaveLength(3);
  });

  it('removes repetitive checksum warning prose from assistant text', () => {
    const text = `âš ï¸ **HDLC Frame Checksum Failure**

The received HDLC frame has an **invalid FCS (Frame Check Sequence)**. This means the frame payload may be corrupted and cannot be reliably decoded.

**Recommended Actions:**
1. Check the physical layer.
2. Request retransmission of the frame.`;

    expect(stripVerboseChecksumExplanation(text)).toBe('');
  });

  it('keeps useful leading text when only the verbose checksum tail should be removed', () => {
    const text = `I could still read the outer HDLC header.

âš ï¸ **HDLC Frame Checksum Failure**

The received HDLC frame has an **invalid FCS (Frame Check Sequence)**.`;

    expect(stripVerboseChecksumExplanation(text)).toBe(
      'I could still read the outer HDLC header.'
    );
  });

  it('compresses verbose greeting replies into a short capability line', () => {
    const text = `Hello! I'm here to assist with your request. Below are the capabilities I can provide:
- Decode HDLC hex frames starting with 7E.
- Analyze SICONIA alarm codes.
- Classify communication logs.
- Address DLMS/COSEM protocol inquiries.`;

    expect(compactGreetingReply(text)).toBe(
      'Hi. I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarm codes, XML traces, and communication logs.'
    );
  });

  it('compresses bullet-prefixed greeting replies into a short capability line', () => {
    const text = `- Hello! I can assist with decoding HDLC frames, analyzing alarm codes, and other related tasks.
- My expertise includes protocol decoding and DLMS/COSEM guidance.`;

    expect(compactGreetingReply(text)).toBe(
      'Hi. I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarm codes, XML traces, and communication logs.'
    );
  });

  it('strips redundant structured explanation bullets when the panel already shows the facts', () => {
    const text = `- The alarm code 0x1342 denotes a critical DCU comm failure impacting system functionality.
- Root cause involves a DCU-HES link malfunction.
- Remediation involves verifying credentials and repairing the affected link.
- Affected component: HES.`;

    expect(stripRedundantStructuredExplanation(text)).toBe('');
  });

  it('strips repetitive log prose when the structured panel already covers it', () => {
    const text = `The log indicates a critical connectivity failure affecting meter12345, signaling an alarm related to network access disruption.
- Root Cause: Connection loss to meter12345 suggests a failure in the communication link.
- Severity: High (ERROR) due to potential downtime and impact on meter operations.
- Affected Component: Meter12345.
- Remediation: Conduct diagnostics to identify root causes.`;

    expect(stripRedundantStructuredExplanation(text)).toBe('');
  });

  it('strips repeated structured prose with glued markdown labels and no-space alarm tokens', () => {
    const text = '**What it means**: Critical alarm0x1342 indicates DCU-01 failure.**Impact**: System instability risk.**Next step**: Verify DCU-HES link.';

    expect(stripRedundantStructuredExplanation(text)).toBe('');
  });

  it('does not blank legitimate DLMS security explanations that mention diagnostics', () => {
    const text = 'AARE diagnostic 6 usually means the association request was rejected because the application context, authentication state, or negotiated security parameters did not match the server expectations.';

    expect(stripRedundantStructuredExplanation(text)).toBe(text);
  });

  it('normalizes compact technical replies for display', () => {
    expect(normalizeTechnicalReply('- The meaning of OBIS1.0.1.8.0.255 is active energy import total.')).toBe(
      'The meaning of OBIS 1.0.1.8.0.255 is active energy import total.'
    );
  });

  it('preserves markdown headings and bold markers for assistant rendering', () => {
    expect(normalizeTechnicalReply('## Association Rejection Diagnostics\n**Practical Troubleshooting Checks**\n- Check authentication.')).toBe(
      '## Association Rejection Diagnostics\n**Practical Troubleshooting Checks**\n- Check authentication.'
    );
  });

  it('restores escaped newlines and clean citation symbols in technical replies', () => {
    expect(normalizeTechnicalReply('Line one.\\nLine two.\\n\\nSources: DLMS Standard ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Ãƒâ€šÃ‚§Green Book')).toBe(
      'Line one.\nLine two.\n\nSources: DLMS Standard — §Green Book'
    );
  });

  it('builds compact summaries for successful decode and SICONIA analysis', () => {
    expect(getDecodeInterpretationSummary(decodeSuccess)).toMatchObject({
      title: 'Interpretation',
      whatItMeans: 'The frame decodes as GET_RESPONSE.',
    });

    expect(getSiconiaInterpretationSummary(alarmAnalysis)).toMatchObject({
      title: 'Interpretation',
      whatItMeans: 'Alarm 0x1342 is HIGH on HES.',
      nextStep: 'Check DCU-HES link, verify credentials.',
    });
  });

  it('builds direct-payload summaries for deterministic OBIS and AXDR inputs', () => {
    expect(getDecodeInterpretationSummary(obisLookupDecode)).toMatchObject({
      title: 'Interpretation',
      whatItMeans: 'OBIS 1.0.1.8.0.255 resolves to Active energy import total in kWh via IC 3 (scaler 10^-3).',
    });

    expect(getDecodeInterpretationSummary(axdrDirectDecode)).toMatchObject({
      title: 'Interpretation',
      whatItMeans: 'The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.',
    });
  });
});

