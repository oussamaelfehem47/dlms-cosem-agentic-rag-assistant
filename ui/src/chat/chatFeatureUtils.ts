import { ConversationEntry, InputClass, JavaDecodeResult, JavaSiconiaResult } from '../types';
import { UploadResult } from '../components/UploadButton';
import { normalizeTechnicalReply } from './assistantMessageUtils';
import { extractTrailingSources } from './citationUtils';
import { normalizeVisibleBlock } from './textNormalization';
import {
  getUserComposerDraftStorageKey,
  getUserConversationMetaStorageKey,
} from '../utils/userScopedStorage';

export type UiConversationCategory = 'security' | 'decode' | 'incident' | 'general';
export type ComposerPreset = Exclude<UiConversationCategory, 'general'>;
export type ExportFormat = 'markdown' | 'text';

export interface LocalConversationMeta {
  pinned: boolean;
  category: UiConversationCategory;
  manualTitle?: boolean;
  autoRetitled?: boolean;
}

export type LocalConversationMetaMap = Record<string, LocalConversationMeta>;

export interface ComposerDraft {
  inputText: string;
  attachments: UploadResult[];
  activePreset: ComposerPreset | null;
}

const SECURITY_RE = /\b(hls|lls|security suite|frame counter|replay|authentication|gmac|ciphering|security policy|challenge|aare|aarq|suite 0|suite 1|suite 2|suite 3)\b/i;
const INCIDENT_RE = /\b(alarm|incident|log block|trace|xml|hes|dcu|retry|failure|reject|disconnected|wan|plc|rf|root cause|troubleshoot)\b/i;
const DECODE_RE = /\b(dlms|cosem|obis|hdlc|apdu|frame decode|decode frame|snrm|ua frame|get-response|association)\b/i;
const GREETING_RE = /^(hi|hello|hey|yo|good morning|good afternoon|good evening|salut|test|thanks(?: a lot)?|thank you(?: very much)?(?: for (?:your )?help)?|many thanks|ok|okay)\b[!. ]*$/i;
const GENERIC_RE = /^(question|chat|new conversation|new chat|help|start)\b[!. ]*$/i;
const CODE_LIKE_RE = /^(0x[0-9a-f]{1,8}|[0-9a-f\s]{18,})$/i;

export function isSecurityPrompt(text: string): boolean {
  return SECURITY_RE.test(text);
}

export function detectConversationCategory(
  text: string,
  inputClass: InputClass = 'query',
): UiConversationCategory {
  if (isSecurityPrompt(text)) return 'security';
  if (inputClass === 'hex_frame') return 'decode';
  if (inputClass === 'xml_trace' || inputClass === 'alarm_code' || inputClass === 'log_block') {
    return 'incident';
  }
  if (INCIDENT_RE.test(text)) return 'incident';
  if (DECODE_RE.test(text)) return 'decode';
  return 'general';
}

export function categoryFromIntent(intent?: string | null): UiConversationCategory | null {
  switch (intent) {
    case 'FRAME_DECODE':
    case 'APDU_ANALYSIS':
    case 'PROFILE_DECODE':
      return 'decode';
    case 'SICONIA_TROUBLESHOOT':
      return 'incident';
    case 'SECURITY_EXPLAIN':
      return 'security';
    case 'DOCUMENTATION':
    case 'OBIS_LOOKUP':
    case 'UNKNOWN':
      return 'general';
    case null:
    case undefined:
      return null;
    default:
      return null;
  }
}

export function resolveConversationCategory(
  text: string,
  inputClass: InputClass = 'query',
  intent?: string | null,
): UiConversationCategory {
  return categoryFromIntent(intent) || detectConversationCategory(text, inputClass);
}

export function detectHistoryCategory(history: ConversationEntry[]): UiConversationCategory {
  const lastUserEntry = [...history].reverse().find((entry) => entry.userInput.trim());
  if (!lastUserEntry) return 'general';
  return resolveConversationCategory(lastUserEntry.userInput, lastUserEntry.inputClass, lastUserEntry.intent);
}

function summarizeText(text: string, fallback: string): string {
  const firstLine = text
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^[\W_]+/, '')
    .slice(0, 96);

  return firstLine || fallback;
}

export function cleanConversationTitleSource(text: string): string {
  const knownLeadIns = [
    /^security:\s*/i,
    /^decode:\s*/i,
    /^incident:\s*/i,
    /^explain this dlms security concept or issue:\s*/i,
    /^decode and explain this dlms\/cosem input:\s*/i,
    /^analyze this incident and suggest the likely root cause:\s*/i,
    /^help me troubleshoot this siconia issue and suggest remediation:\s*/i,
    /^explain this dlms frame step by step and highlight anomalies:\s*/i,
  ];

  let normalized = text.replace(/\s+/g, ' ').trim();

  for (const pattern of knownLeadIns) {
    normalized = normalized.replace(pattern, '');
  }

  normalized = normalized
    .replace(/^(explain|analyze|analyse|decode|troubleshoot)\s+/i, '')
    .replace(/^help me\s+/i, '')
    .replace(/^(this|the)\s+/i, '')
    .trim();

  if (!normalized) return normalized;
  return normalized.charAt(0).toUpperCase() + normalized.slice(1);
}

export function isWeakConversationTitle(title: string): boolean {
  const normalized = cleanConversationTitleSource(title).trim();
  if (!normalized) return true;
  if (GREETING_RE.test(normalized)) return true;
  if (GENERIC_RE.test(normalized)) return true;
  if (/^new conversation$/i.test(normalized)) return true;
  if (normalized.length <= 3) return true;
  if (CODE_LIKE_RE.test(normalized)) return true;
  return false;
}

export function suggestConversationTitle(
  sourceText: string,
  attachments: UploadResult[],
  category: UiConversationCategory,
): string {
  const attachmentName = attachments[0]?.filename || '';
  const cleanedSource = cleanConversationTitleSource(sourceText);
  const summary = summarizeText(cleanedSource, attachmentName || 'New conversation');

  if (category === 'decode' && /^[0-9a-fA-F\s]{10,}$/.test(summary.replace(/^0x/i, ''))) {
    return summary.slice(0, 96);
  }

  if (GREETING_RE.test(summary) || GENERIC_RE.test(summary)) {
    return '';
  }

  return summary;
}

export function suggestAutoRenameTitle(
  currentTitle: string,
  sourceText: string,
  attachments: UploadResult[],
  category: UiConversationCategory,
  options?: { manualTitle?: boolean; autoRetitled?: boolean }
): string | null {
  if (options?.manualTitle || options?.autoRetitled) {
    return null;
  }

  if (!isWeakConversationTitle(currentTitle)) {
    return null;
  }

  const candidate = suggestConversationTitle(sourceText, attachments, category).trim();
  if (!candidate || isWeakConversationTitle(candidate)) {
    return null;
  }

  if (candidate.localeCompare(currentTitle.trim(), undefined, { sensitivity: 'base' }) === 0) {
    return null;
  }

  return candidate;
}

export function resolveSuggestedEndpoint(
  attachments: UploadResult[],
): 'decode' | 'siconia' | undefined {
  const endpoints = attachments
    .map((attachment) => attachment.suggestedEndpoint)
    .filter((endpoint): endpoint is 'decode' | 'siconia' => Boolean(endpoint));

  if (endpoints.length === 0) return undefined;
  if (endpoints.every((endpoint) => endpoint === endpoints[0])) return endpoints[0];
  return undefined;
}

export function loadConversationMetaMap(userId: string): LocalConversationMetaMap {
  if (!userId) return {};
  try {
    const raw = localStorage.getItem(getUserConversationMetaStorageKey(userId));
    if (!raw) return {};
    const parsed = JSON.parse(raw) as LocalConversationMetaMap;
    if (typeof parsed !== 'object' || !parsed) return {};

    return Object.fromEntries(
      Object.entries(parsed).map(([conversationId, meta]) => [
        conversationId,
        {
          pinned: Boolean(meta?.pinned),
          category: meta?.category || 'general',
          manualTitle: Boolean(meta?.manualTitle),
          autoRetitled: Boolean(meta?.autoRetitled),
        },
      ])
    );
  } catch {
    return {};
  }
}

export function saveConversationMetaMap(userId: string, meta: LocalConversationMetaMap): void {
  if (!userId) return;
  localStorage.setItem(getUserConversationMetaStorageKey(userId), JSON.stringify(meta));
}

export function loadComposerDraft(userId: string, conversationId: string | null): ComposerDraft | null {
  if (!userId) return null;
  try {
    const raw = localStorage.getItem(getUserComposerDraftStorageKey(userId, conversationId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ComposerDraft;
    if (!parsed || typeof parsed !== 'object') return null;
    return {
      inputText: typeof parsed.inputText === 'string' ? parsed.inputText : '',
      attachments: Array.isArray(parsed.attachments) ? parsed.attachments : [],
      activePreset:
        parsed.activePreset === 'security' ||
        parsed.activePreset === 'decode' ||
        parsed.activePreset === 'incident'
          ? parsed.activePreset
          : null,
    };
  } catch {
    return null;
  }
}

export function saveComposerDraft(
  userId: string,
  conversationId: string | null,
  draft: ComposerDraft,
): void {
  if (!userId) return;
  localStorage.setItem(getUserComposerDraftStorageKey(userId, conversationId), JSON.stringify(draft));
}

export function clearComposerDraft(userId: string, conversationId: string | null): void {
  if (!userId) return;
  localStorage.removeItem(getUserComposerDraftStorageKey(userId, conversationId));
}

function getStructuredPayload(entry: ConversationEntry): unknown {
  if (entry.artifactResults && entry.artifactResults.length > 0) {
    return entry.artifactResults;
  }
  return entry.siconiaAnalysis || entry.decodeResult || null;
}

function getFrameSummaryLabel(decode: JavaDecodeResult): string {
  const frame = decode.hdlcFrame;
  if (!frame?.frameType) return 'Decode result attached';
  if (frame.frameType === 'U_FRAME' && frame.uFrameType) {
    return `Decode result: ${frame.frameType} (${frame.uFrameType})`;
  }
  if (frame.frameType === 'S_FRAME' && frame.sFrameType) {
    return `Decode result: ${frame.frameType} (${frame.sFrameType})`;
  }
  return `Decode result: ${frame.frameType}`;
}

function structuredSummary(entry: ConversationEntry): string | null {
  if (entry.artifactResults && entry.artifactResults.length > 0) {
    return `Artifact results: ${entry.artifactResults.length}`;
  }
  if (entry.decodeResult) {
    const decode = entry.decodeResult as JavaDecodeResult;
    const normalizedKind = decode.processingMetadata?.normalizedKind;

    if (normalizedKind === 'OBIS_QUERY') {
      return 'Decode result: OBIS Lookup';
    }
    if (normalizedKind === 'AXDR_HEX') {
      return 'Decode result: AXDR Decode';
    }
    if (normalizedKind === 'APDU_HEX' && typeof decode.apduType === 'string' && decode.apduType !== 'UNKNOWN') {
      return `Decode result: APDU Decode - ${decode.apduType}`;
    }
    if (decode.hdlcFrame) {
      return getFrameSummaryLabel(decode);
    }
    if (typeof decode.apduType === 'string') {
      return `Decode result: ${decode.apduType}`;
    }
    return 'Decode result attached';
  }

  if (entry.siconiaAnalysis) {
    const analysis = entry.siconiaAnalysis as JavaSiconiaResult;
    if (Array.isArray(analysis.alarmResults)) {
      return `SICONIA analysis: ${analysis.alarmResults.length} alarm result(s)`;
    }
    if (analysis.logAnalysis) {
      return `SICONIA analysis: ${analysis.logAnalysis.dominantLayer}`;
    }
    return 'SICONIA analysis attached';
  }

  return null;
}

export function buildConversationExport(
  title: string,
  history: ConversationEntry[],
  format: ExportFormat,
): string {
  const exportTitle = title || 'Conversation export';

  if (format === 'text') {
    return normalizeVisibleBlock([
      exportTitle,
      '='.repeat(exportTitle.length),
      '',
      ...history.flatMap((entry, index) => {
        const explanation = normalizeTechnicalReply(entry.explanation || '');
        const { bodyText, citations } = extractTrailingSources(explanation);
        const lines = [
          `Turn ${index + 1} - User`,
          entry.userInput,
          '',
          'Assistant',
          bodyText,
        ];
        if (citations.length > 0) {
          lines.push('', 'Sources', ...citations.map((citation) => `- ${citation}`));
        }
        const summary = structuredSummary(entry);
        if (summary) {
          lines.push('', summary, JSON.stringify(getStructuredPayload(entry), null, 2));
        }
        return [...lines, ''];
      }),
    ].join('\n'));
  }

  return normalizeVisibleBlock([
    `# ${exportTitle}`,
    '',
    ...history.flatMap((entry, index) => {
      const explanation = normalizeTechnicalReply(entry.explanation || '');
      const { bodyText, citations } = extractTrailingSources(explanation);
      const lines = [
        `## Turn ${index + 1}`,
        '',
        '### User',
        '',
        entry.userInput,
        '',
        '### Assistant',
        '',
        bodyText,
      ];
      if (citations.length > 0) {
        lines.push('', '### Sources', '', ...citations.map((citation) => `- ${citation}`));
      }
      const payload = getStructuredPayload(entry);
      const summary = structuredSummary(entry);
      if (payload) {
        lines.push(
          '',
          '### Structured Result',
          '',
          summary || 'Structured payload',
          '',
          '```json',
          JSON.stringify(payload, null, 2),
          '```',
        );
      }
      lines.push('');
      return lines;
    }),
  ].join('\n'));
}

export function downloadConversationExport(
  title: string,
  history: ConversationEntry[],
  format: ExportFormat,
): void {
  const content = buildConversationExport(title, history, format);
  const extension = format === 'markdown' ? 'md' : 'txt';
  const safeTitle = (title || 'conversation')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48) || 'conversation';
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${safeTitle}.${extension}`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
