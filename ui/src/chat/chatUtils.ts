import {
  ConversationEntry,
  InputClass,
  JavaArtifactResult,
  JavaDecodeResult,
  JavaOrchestrationMode,
  JavaStrategyMetadata,
  JavaToolTraceEntry,
  WorkflowArtifactInput,
  JavaSiconiaResult,
  SiconiaAnalysis,
} from '../types';
import { Message } from '../hooks/useConversations';
import { UploadResult } from '../components/UploadButton';

function isJavaSiconiaResult(value: unknown): value is JavaSiconiaResult {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return Array.isArray(candidate.alarmResults)
    || typeof candidate.logAnalysis === 'object'
    || typeof candidate.xmlTrace === 'object'
    || (typeof candidate.processingMetadata === 'object'
      && candidate.processingMetadata !== null
      && typeof (candidate.processingMetadata as Record<string, unknown>).normalizedInputClass === 'string');
}

function isJavaDecodeResult(value: unknown): value is JavaDecodeResult {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.apduType === 'string'
    || typeof candidate.hdlcFrame === 'object'
    || (typeof candidate.processingMetadata === 'object'
      && candidate.processingMetadata !== null
      && typeof (candidate.processingMetadata as Record<string, unknown>).normalizedKind === 'string');
}

function isLegacySiconiaAnalysis(value: unknown): value is SiconiaAnalysis {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.input_type === 'string'
    || Array.isArray(candidate.alarms_decoded)
    || typeof candidate.log_summary === 'object';
}

function isJavaStrategyMetadata(value: unknown): value is JavaStrategyMetadata {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.selectedStrategy === 'string'
    && typeof candidate.selectedLabel === 'string'
    && Array.isArray(candidate.candidates);
}

function isJavaOrchestrationMode(value: unknown): value is JavaOrchestrationMode {
  return value === 'DETERMINISTIC_FAST_PATH'
    || value === 'STRUCTURED_PLUS_AGENTIC'
    || value === 'NATURAL_LANGUAGE_AGENTIC'
    || value === 'AMBIGUOUS_SAFE_FALLBACK';
}

function isJavaToolTraceEntry(value: unknown): value is JavaToolTraceEntry {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.toolName === 'string'
    && typeof candidate.summary === 'string'
    && typeof candidate.authoritative === 'boolean'
    && typeof candidate.provenance === 'string';
}

function isJavaToolTraceList(value: unknown): value is JavaToolTraceEntry[] {
  return Array.isArray(value) && value.every(isJavaToolTraceEntry);
}

export function dbMessagesToHistory(messages: Message[]): ConversationEntry[] {
  return messages.map((message) => {
    const structuredResult = message.decode_result;
    const siconiaAnalysis =
      !isJavaDecodeResult(structuredResult)
      && (isJavaSiconiaResult(structuredResult) || isLegacySiconiaAnalysis(structuredResult))
        ? structuredResult
        : null;
    const decodeResult = siconiaAnalysis
      ? null
      : (structuredResult as ConversationEntry['decodeResult']);

    return {
      id: message.id,
      timestamp: new Date(message.created_at),
      inputClass: (message.input_class || 'query') as InputClass,
      userInput: message.raw_input || '',
      decodeResult: decodeResult || null,
      siconiaAnalysis,
      artifactResults: Array.isArray(message.artifact_results)
        ? message.artifact_results as JavaArtifactResult[]
        : null,
      explanation: message.explanation || '',
      sessionId: message.session_id || '',
      usedFallback: message.used_mcp_fallback || false,
      explanationMode: message.explanation_mode || null,
      toolProvenance: message.tool_provenance || null,
      orchestrationMode: isJavaOrchestrationMode(message.orchestration_mode) ? message.orchestration_mode : null,
      plannerUsed: typeof message.planner_used === 'boolean' ? message.planner_used : null,
      toolTrace: isJavaToolTraceList(message.tool_trace) ? message.tool_trace : null,
      plannerFallbackReason: message.planner_fallback_reason || null,
      intent: message.intent || undefined,
      strategyMetadata: isJavaStrategyMetadata(message.strategy_metadata) ? message.strategy_metadata : null,
    };
  });
}

export function buildSubmittedInput(inputText: string, attachments: UploadResult[]): string {
  const trimmedInput = inputText.trim();
  const attachmentBlocks = attachments
    .map((attachment, index) => {
      const text = attachment.text.trim();
      if (!text) return '';
      if (attachments.length === 1) return text;
      return `[Attachment ${index + 1}: ${attachment.filename}]\n${text}`;
    })
    .filter(Boolean);
  const attachmentText = attachmentBlocks.join('\n\n');

  if (trimmedInput && attachmentText) {
    return `${attachmentText}\n\n[User context]\n${trimmedInput}`;
  }

  return trimmedInput || attachmentText;
}

export function buildRequestArtifacts(attachments: UploadResult[]): WorkflowArtifactInput[] {
  return attachments
    .filter((attachment) => attachment.text.trim().length > 0)
    .map((attachment) => ({
      source: 'ATTACHMENT',
      filename: attachment.filename,
      text: attachment.text.trim(),
      hintedInputClass: attachment.inputClass,
      suggestedEndpoint: attachment.suggestedEndpoint ?? null,
    }));
}

export function fileIcon(type: string): string {
  const map: Record<string, string> = {
    'application/pdf': '📄',
    'text/plain': '📝',
    'application/xml': '🧾',
    'text/xml': '🧾',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': '📘',
  };
  return map[type] || '📎';
}
