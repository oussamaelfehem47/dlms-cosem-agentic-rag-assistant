import {
  JavaDecodeResult,
  JavaDlmsNormalizedKind,
  JavaObisResolution,
  JavaSiconiaResult,
} from '../types';
import { normalizeVisibleBlock } from './textNormalization';

export interface DecodeFailureSummary {
  title: string;
  whatHappened: string;
  canTrustIt: string;
  nextSteps: string[];
}

export interface AssistantInterpretationSummary {
  title: string;
  whatItMeans: string;
  impact?: string;
  canTrustIt?: string;
  nextStep?: string;
}

export function hasInvalidFcs(javaDecode: JavaDecodeResult | null): boolean {
  if (!javaDecode) return false;
  if (javaDecode.hdlcFrame?.fcsValid === false) return true;
  return javaDecode.parseErrors?.some((error) => /fcs invalid/i.test(error)) ?? false;
}

function uniqueNonFcsErrors(javaDecode: JavaDecodeResult | null): string[] {
  if (!javaDecode?.parseErrors) return [];

  const unique = new Set<string>();
  for (const error of javaDecode.parseErrors) {
    const trimmed = error.trim();
    if (!trimmed || /fcs invalid/i.test(trimmed)) continue;
    unique.add(trimmed);
  }
  return Array.from(unique);
}

function ensureSentence(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

function getFrameLabel(javaDecode: JavaDecodeResult): string {
  const frame = javaDecode.hdlcFrame;
  if (!frame?.frameType) return 'frame';
  if (frame.frameType === 'S_FRAME' && frame.sFrameType) {
    return `${frame.frameType} (${frame.sFrameType})`;
  }
  if (frame.frameType === 'U_FRAME' && frame.uFrameType) {
    return `${frame.frameType} (${frame.uFrameType})`;
  }
  return frame.frameType;
}

function summarizeObisResolution(resolution: JavaObisResolution): string {
  const unitPart = resolution.unit ? ` ${resolution.unit}` : '';
  const scalerPart =
    resolution.scaler != null && resolution.scaler !== 0 ? ` (scaler 10^${resolution.scaler})` : '';
  const icPart = resolution.icName
    ? `IC ${resolution.ic} (${resolution.icName})`
    : `IC ${resolution.ic}`;
  return `${resolution.description}${unitPart ? ` in${unitPart}` : ''} via ${icPart}${scalerPart}`;
}

function getDirectPayloadSummary(
  javaDecode: JavaDecodeResult,
  normalizedKind: JavaDlmsNormalizedKind,
): AssistantInterpretationSummary {
  if (normalizedKind === 'OBIS_QUERY') {
    const resolution = javaDecode.obisResolutions?.[0];
    if (!resolution) {
      return {
        title: 'Interpretation',
        whatItMeans: 'The request was handled as a deterministic OBIS lookup.',
        nextStep: 'Use the structured panel for the resolved OBIS details.',
      };
    }
    return {
      title: 'Interpretation',
      whatItMeans: `OBIS ${resolution.obis} resolves to ${summarizeObisResolution(resolution)}.`,
      impact: 'The meaning comes from deterministic OBIS resolution, not from a guessed frame decode.',
      nextStep: 'Use the structured panel for unit, scaler, and interface-class details.',
    };
  }

  if (normalizedKind === 'APDU_HEX') {
    const obisHint = (javaDecode.obisResolutions?.length ?? 0) > 0
      ? 'Recovered OBIS details are available in the structured panel.'
      : javaDecode.axdrTree
        ? 'Structured AXDR details are available in the panel.'
        : 'Use the structured panel for the decoded APDU details.';
    return {
      title: 'Interpretation',
      whatItMeans: `The payload decodes as ${javaDecode.apduType || 'an APDU'} without an HDLC envelope.`,
      impact: obisHint,
      nextStep: 'Use the structured panel for APDU, AXDR, and OBIS details.',
    };
  }

  return {
    title: 'Interpretation',
    whatItMeans: 'The payload was decoded deterministically as raw AXDR without an APDU or HDLC envelope.',
    impact: (javaDecode.obisResolutions?.length ?? 0) > 0
      ? 'Recovered OBIS/object identifiers are available in the structured panel.'
      : 'The structured AXDR tree is available in the panel.',
    nextStep: 'Use the structured panel to inspect the AXDR hierarchy and recovered values.',
  };
}

export function getDecodeFailureSummary(
  javaDecode: JavaDecodeResult | null,
): DecodeFailureSummary | null {
  if (!hasInvalidFcs(javaDecode) || !javaDecode) return null;

  const structuralIssue = uniqueNonFcsErrors(javaDecode)[0];
  if (structuralIssue) {
    return {
      title: 'Checksum failed',
      whatHappened: `The outer HDLC header parses as ${getFrameLabel(javaDecode)}, but ${ensureSentence(structuralIssue).toLowerCase()} The FCS check also failed, so the capture may be malformed or corrupted.`,
      canTrustIt: 'Only the outer HDLC classification should be treated as tentative. Do not trust any higher-layer payload interpretation from this capture.',
      nextSteps: [
        'Re-capture or retransmit the same frame before trusting the decode.',
        'Verify whether the source uses an extra header variant or whether the capture itself is malformed.',
        'If the issue repeats, inspect the communication path for corruption, framing issues, or capture noise.',
      ],
    };
  }

  return {
    title: 'Checksum failed',
    whatHappened: `The FCS check failed for this ${getFrameLabel(javaDecode)}. The bytes were received, but the integrity check does not match.`,
    canTrustIt: 'Only treat the outer HDLC header as tentative. Payload, APDU details, and parsed values may be wrong because the frame may be corrupted.',
    nextSteps: [
      'Re-capture or retransmit the same frame before trusting the decode.',
      'Compare against adjacent frames to see whether this is an isolated bad packet.',
      'If it keeps happening, inspect the communication path for noise, weak signal, or wiring issues.',
    ],
  };
}

export function stripVerboseChecksumExplanation(text: string): string {
  if (!text.trim()) return '';

  const blockPatterns = [
    /âš ï¸\s*\*\*HDLC Frame Checksum Failure\*\*[\s\S]*$/i,
    /\*\*HDLC Frame Checksum Failure\*\*[\s\S]*$/i,
    /The received HDLC frame has an \*\*invalid FCS[\s\S]*$/i,
  ];

  let cleaned = text;
  for (const pattern of blockPatterns) {
    cleaned = cleaned.replace(pattern, '').trim();
  }
  cleaned = cleaned
    .split('\n')
    .filter((line) => {
      const trimmed = line.trim();
      if (!trimmed) return false;
      if (trimmed === 'Ã¢Å¡Â Ã¯Â¸Â' || trimmed === 'âš ï¸' || trimmed === 'ÃƒÂ¢Ã…Â¡Ã‚Â ÃƒÂ¯Ã‚Â¸Ã‚Â') {
        return false;
      }
      return !/^\s*[^\p{L}\p{N}]+\s*$/u.test(trimmed);
    })
    .join('\n')
    .trim();

  return cleaned;
}

function sentence(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

export function compactGreetingReply(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';

  const greetingLike = /^(?:[-*â€¢]\s*)*(hello|hi|hey|good morning|good afternoon|good evening)\b/i.test(trimmed);
  const capabilityLike = /\b(decode|hdlc|alarm code|xml|logs?|dlms\/cosem|protocol questions?)\b/i.test(trimmed);

  if (!greetingLike || !capabilityLike) {
    return trimmed;
  }

  return 'Hi. I can help with DLMS/COSEM questions, HDLC frame decode, SICONIA alarm codes, XML traces, and communication logs.';
}

export function stripRedundantStructuredExplanation(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';

  const repetitiveParagraphPatterns = [
    /the alarm code\s*0x[0-9a-f]+\s+(corresponds to|denotes|signifies)/i,
    /\*\*what it means\*\*:\s*critical\s+alarm\s*0x[0-9a-f]+/i,
    /\*\*impact\*\*:/i,
    /\*\*next step\*\*:/i,
    /this failure directly affects system communication reliability/i,
    /severity escalation arises from/i,
    /remediation involves/i,
    /affected component:/i,
    /xml trace data available/i,
    /the root cause is a failed connection/i,
    /the log indicates a critical connectivity failure/i,
    /severity escalates due to the direct impact/i,
    /conduct diagnostics to identify root causes/i,
    /the specified meter/i,
  ];

  if (repetitiveParagraphPatterns.some((pattern) => pattern.test(trimmed))) {
    return '';
  }

  const lines = trimmed
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  const filtered = lines.filter((line) => {
    if (!line.startsWith('-')) return true;

    return !/(alarm code|root cause|remediation|affected component|severity|dominant layer|issue categories|xml trace data|connection to meter|communication failure|system functionality|operational disruption)/i.test(line);
  });

  if (filtered.length === 0) return '';

  const allBulletBoilerplate = filtered.every((line) => line.startsWith('-'));
  if (allBulletBoilerplate) return '';

  return filtered.join('\n').trim();
}

export function normalizeTechnicalReply(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';

  let normalized = normalizeVisibleBlock(trimmed)
    .replace(/\bOBIS(?=\d)/g, 'OBIS ')
    .replace(/\bIC(?=\d)/g, 'IC ')
    .replace(/,,+/g, ',')
    .trim();

  if (!normalized.includes('\n') && /^[-*]\s+/.test(normalized)) {
    normalized = normalized.replace(/^[-*]\s+/, '');
  }

  return normalized;
}

export function formatStructuredNarrationForMarkdown(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return '';

  return trimmed.replace(
    /\n(?=(?:What it means|Why it matters|Impact|Can I trust it|Next step|What happened|What to do next):)/g,
    '\n\n',
  );
}

export function getDecodeInterpretationSummary(
  javaDecode: JavaDecodeResult | null,
): AssistantInterpretationSummary | null {
  if (!javaDecode || hasInvalidFcs(javaDecode)) return null;

  const normalizedKind = javaDecode.processingMetadata?.normalizedKind;
  if (normalizedKind && !javaDecode.hdlcFrame && normalizedKind !== 'FRAME_HEX') {
    return getDirectPayloadSummary(javaDecode, normalizedKind);
  }

  const frameType = javaDecode.hdlcFrame?.frameType;

  if (frameType === 'U_FRAME') {
    const subtype = javaDecode.hdlcFrame?.uFrameType || 'U-frame';
      return {
      title: 'Interpretation',
      whatItMeans: `This is an HDLC ${subtype} control frame.`,
      impact: 'It represents link-layer state, not an APDU payload.',
      nextStep: 'Use the structured panel to verify SAPs, subtype, and FCS status.',
    };
  }

  if (frameType === 'S_FRAME') {
    const subtype = javaDecode.hdlcFrame?.sFrameType || 'S-frame';
    return {
      title: 'Interpretation',
      whatItMeans: `This is an HDLC ${subtype} supervisory frame.`,
      impact: 'It represents acknowledgement or flow control, not an APDU payload.',
      nextStep: 'Use the structured panel to verify SAPs, subtype, and FCS status.',
    };
  }

  const hasParseErrors = (javaDecode.parseErrors?.length ?? 0) > 0;
  if (javaDecode.apduType === 'UNKNOWN' || hasParseErrors) {
    return {
      title: 'Interpretation',
      whatItMeans: 'The deterministic decoder could not identify a valid DLMS payload for this frame.',
      canTrustIt: 'Only trust the raw frame metadata shown in the structured panel until the capture is verified.',
      nextStep: 'Re-check frame boundaries, encryption state, and capture integrity before retrying.',
    };
  }

  const profileHint = javaDecode.profileResult
    ? 'Profile data is available in the structured panel.'
    : (javaDecode.obisResolutions?.length ?? 0) > 0
      ? 'OBIS and object details are available in the structured panel.'
      : 'Structured decode details are available in the panel.';

  return {
    title: 'Interpretation',
    whatItMeans: `The frame decodes as ${javaDecode.apduType}.`,
    impact: profileHint,
    nextStep: 'Use the structured panel for HDLC, APDU, AXDR, and OBIS details.',
  };
}

export function getSiconiaInterpretationSummary(
  javaSiconia: JavaSiconiaResult | null,
): AssistantInterpretationSummary | null {
  if (!javaSiconia) return null;

  if (javaSiconia.alarmResults && javaSiconia.alarmResults.length > 0) {
    if (javaSiconia.alarmResults.length === 1) {
      const alarm = javaSiconia.alarmResults[0];
      return {
        title: 'Interpretation',
        whatItMeans: `Alarm ${alarm.code} is ${alarm.severity} on ${alarm.affectedComponent}.`,
        impact: sentence(alarm.rootCause),
        nextStep: sentence(alarm.remediation),
      };
    }

    return {
      title: 'Interpretation',
      whatItMeans: `${javaSiconia.alarmResults.length} alarms were decoded from this input.`,
      impact: 'Severity, component, root cause, and remediation are listed for each alarm in the structured panel.',
      nextStep: 'Address the highest-severity alarms first, then review the remaining entries.',
    };
  }

  if (javaSiconia.logAnalysis) {
    const categories = javaSiconia.logAnalysis.issueCategories.join(', ');
    return {
      title: 'Interpretation',
      whatItMeans: `The log is dominated by ${javaSiconia.logAnalysis.dominantLayer} issues.`,
      impact: `${javaSiconia.logAnalysis.highestSeverity} severity with ${javaSiconia.logAnalysis.errorLineCount} error lines out of ${javaSiconia.logAnalysis.lineCount}.`,
      nextStep: categories
        ? `Inspect the ${javaSiconia.logAnalysis.dominantLayer} path and the listed categories: ${categories}.`
        : `Inspect the ${javaSiconia.logAnalysis.dominantLayer} path for the observed errors.`,
    };
  }

  if (javaSiconia.xmlTrace) {
    return {
      title: 'Interpretation',
      whatItMeans: 'The input was classified as an XML trace.',
      impact: 'Structured XML details are available in the panel.',
      nextStep: 'Use the trace details to verify device, timestamp, alarm code, and severity fields.',
    };
  }

  return null;
}

