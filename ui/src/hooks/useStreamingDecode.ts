import { useState, useCallback, useRef, useEffect } from 'react';
import {
  DecodeResult,
  SiconiaAnalysis,
  ConversationEntry,
  InputClass,
  JavaArtifactResult,
  JavaDecodeResult,
  JavaExplanationMode,
  JavaOrchestrationMode,
  JavaStrategyMetadata,
  JavaSiconiaResult,
  JavaToolTraceEntry,
  JavaToolProvenance,
  StructuredMessagePayload,
  WorkflowArtifactInput,
} from '../types';
import { SaveMessageParams } from './useConversations';

const LOG_ENTRY_START_PATTERN = /\d{4}[-/]\d{2}[-/]\d{2}(?:\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?)?\s+\[(?:WAN|PLC|RF|HES|DLMS)\]/i;
const LOG_ENTRY_BOUNDARY_PATTERN = /\s+(?=\d{4}[-/]\d{2}[-/]\d{2}(?:\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?)?\s+\[(?:WAN|PLC|RF|HES|DLMS)\])/gi;
const DLMS_SECURITY_OVERRIDE = /\b(aare|aarq|hls|lls|gmac|dlms|cosem|obis|apdu|axdr|hdlc|association|diagnostic|security|suite)\b/i;
const STREAM_DISPLAY_CHUNK_PATTERN = /\S+\s*|\s+/g;
const STREAM_DISPLAY_DELAY_MS = import.meta.env.MODE === 'test' ? 0 : 14;

function splitStreamDisplayChunks(text: string): string[] {
  const matches = text.match(STREAM_DISPLAY_CHUNK_PATTERN);
  return matches && matches.length > 0 ? matches : [text];
}

function normalizeWrappedLogInput(input: string): string {
  const firstLogEntry = input.search(LOG_ENTRY_START_PATTERN);
  if (firstLogEntry < 0) {
    return input;
  }

  const prefix = input.slice(0, firstLogEntry).trim();
  const logBody = input.slice(firstLogEntry).trim();
  const entries = logBody.split(LOG_ENTRY_BOUNDARY_PATTERN).map((entry) => entry.trim()).filter(Boolean);
  if (entries.length < 2) {
    return input;
  }

  const normalizedEntries = entries.join('\n');
  return prefix ? `${prefix}\n${normalizedEntries}` : normalizedEntries;
}

function detectInputClass(input: string): InputClass {
  const s = input.trim();
  const compact = s.replace(/\s+/g, ' ');
  const normalizedForLogs = normalizeWrappedLogInput(s);
  // Strip 0x prefix so "0x7EA0..." is treated as a hex frame, not an alarm code
  const stripped = /^0x/i.test(s) ? s.slice(2) : s;
  const normalizedHex = stripped.replace(/[\s:]+/g, '');
  // DLMS Hex frame hint: actual frame evidence must start and end with 7E.
  if (/^[0-9a-fA-F\s:]{10,}$/.test(stripped)
    && /^7[Ee][0-9A-Fa-f]{10,}7[Ee]$/.test(normalizedHex))
    return 'hex_frame';
  // XML trace: direct XML or XML embedded inside broader prose.
  if (/<[A-Za-z_][\w:.-]*(?:\s[^>]*)?>[\s\S]*<\/[A-Za-z_][\w:.-]*>/m.test(s) || (/<\w/.test(s) && /\/>/.test(s))) {
    return 'xml_trace';
  }
  if (DLMS_SECURITY_OVERRIDE.test(compact)) return 'query';
  // Alarm code: 0x prefix + short hex value (≤8 hex digits = max 4 bytes, e.g. 0x1342)
  if (/^0x[0-9a-fA-F]{1,8}$/i.test(s)) return 'alarm_code';
  if (
    /\b0x[0-9a-fA-F]{1,8}\b/i.test(s)
    && /\b(alarm|critical|warning|warn|severity|device|dcu|concentrator|meter|hes)\b/i.test(compact)
  ) {
    return 'alarm_code';
  }
  if (/\b[A-Z][A-Z0-9_]{2,}\b/.test(s) && /\b(alarm|meaning|mean|severity|root cause|remediation)\b/i.test(compact)) {
    return 'alarm_code';
  }
  // Log block: single-line with timestamp pattern + [LAYER] + severity
  // e.g. "2024-03-20 10:00:01 [WAN] ERROR: Connection timeout"
  if (/^\d{4}[-/]\d{2}[-/]\d{2}.*\[(WAN|PLC|RF|HES|DLMS)\].*(ERROR|WARN|INFO|DEBUG)/i.test(normalizedForLogs))
    return 'log_block';
  if (/\d{4}[-/]\d{2}[-/]\d{2}.*\[(WAN|PLC|RF|HES|DLMS)\].*(ERROR|WARN|INFO|DEBUG|CRITICAL)/i.test(normalizedForLogs))
    return 'log_block';
  // Log block: multi-line with keywords
  const lines = normalizedForLogs.split('\n').filter(l => l.trim());
  if (lines.length >= 2) {
    const hits = lines.filter(l =>
      /\b(INFO|ERROR|WARN|DEBUG|CRITICAL|PLC|WAN|RF|HES|DLMS|alarm)\b/i.test(l)
    ).length;
    if (hits >= 2) return 'log_block';
  }
  return 'query';
}

async function readApiError(response: Response, fallback: string): Promise<string> {
  const raw = await response.text().catch(() => '');
  if (!raw) return fallback;

  try {
    const parsed = JSON.parse(raw) as { detail?: string; error?: string; message?: string };
    return parsed.detail || parsed.error || parsed.message || fallback;
  } catch {
    return raw.trim() || fallback;
  }
}

function getEventPayload(data: Record<string, unknown>): Record<string, unknown> {
  const metadata = data.metadata;
  return metadata && typeof metadata === 'object'
    ? metadata as Record<string, unknown>
    : data;
}

function getEventString(
  data: Record<string, unknown>,
  camelKey: string,
  snakeKey?: string,
): string | null {
  const payload = getEventPayload(data);
  const snake = snakeKey ?? camelKey.replace(/[A-Z]/g, (match) => `_${match.toLowerCase()}`);
  const value = payload[camelKey] ?? payload[snake] ?? data[camelKey] ?? data[snake];
  return typeof value === 'string' ? value : null;
}

function getEventBoolean(
  data: Record<string, unknown>,
  camelKey: string,
  snakeKey?: string,
): boolean | null {
  const payload = getEventPayload(data);
  const snake = snakeKey ?? camelKey.replace(/[A-Z]/g, (match) => `_${match.toLowerCase()}`);
  const value = payload[camelKey] ?? payload[snake] ?? data[camelKey] ?? data[snake];
  return typeof value === 'boolean' ? value : null;
}

function getEventArray<T = unknown>(
  data: Record<string, unknown>,
  camelKey: string,
  snakeKey?: string,
): T[] | null {
  const payload = getEventPayload(data);
  const snake = snakeKey ?? camelKey.replace(/[A-Z]/g, (match) => `_${match.toLowerCase()}`);
  const value = payload[camelKey] ?? payload[snake] ?? data[camelKey] ?? data[snake];
  return Array.isArray(value) ? value as T[] : null;
}

function getEventObject<T>(
  data: Record<string, unknown>,
  camelKey: string,
  snakeKey?: string,
): T | null {
  const payload = getEventPayload(data);
  const snake = snakeKey ?? camelKey.replace(/[A-Z]/g, (match) => `_${match.toLowerCase()}`);
  const value = payload[camelKey] ?? payload[snake] ?? data[camelKey] ?? data[snake];
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as T
    : null;
}

interface StreamState {
  isStreaming: boolean;
  decodeResult: DecodeResult | JavaDecodeResult | null;
  siconiaAnalysis: SiconiaAnalysis | JavaSiconiaResult | null;
  artifactResults: JavaArtifactResult[] | null;
  streamingText: string;
  usedFallback: boolean;
  explanationMode: JavaExplanationMode | null;
  toolProvenance: JavaToolProvenance | null;
  orchestrationMode: JavaOrchestrationMode | null;
  plannerUsed: boolean | null;
  toolTrace: JavaToolTraceEntry[] | null;
  plannerFallbackReason: string | null;
  error: string | null;
  sessionId: string | null;
  lastIntent: string | null;
  strategyMetadata: JavaStrategyMetadata | null;
}

interface FinalizedStreamContext {
  inputClass: InputClass;
  userInput: string;
  finalDecodeResultRef: { current: DecodeResult | JavaDecodeResult | null };
  finalSiconiaAnalysisRef: { current: SiconiaAnalysis | JavaSiconiaResult | null };
  artifactResultsRef: { current: JavaArtifactResult[] | null };
  finalSessionIdRef: { current: string | null };
  finalIntentRef: { current: string | null };
  finalStrategyMetadataRef: { current: JavaStrategyMetadata | null };
  orchestrationModeRef: { current: JavaOrchestrationMode | null };
  plannerUsedRef: { current: boolean | null };
  toolTraceRef: { current: JavaToolTraceEntry[] | null };
  plannerFallbackReasonRef: { current: string | null };
  fullTextRef: { current: string };
  usedFallbackRef: { current: boolean };
  explanationModeRef: { current: JavaExplanationMode | null };
  toolProvenanceRef: { current: JavaToolProvenance | null };
  enqueueDisplayToken: (token: string) => void;
  flushDisplayQueue: () => Promise<void>;
}

export function useStreamingDecode(
  apiKey?: string | null,
  jwtToken?: string | null,
  refreshToken?: () => Promise<boolean>,
  userId?: string | null,
  saveMessage?: (params: SaveMessageParams) => Promise<boolean>
) {
  const [state, setState] = useState<StreamState>({
    isStreaming: false,
    decodeResult: null,
    siconiaAnalysis: null,
    artifactResults: null,
    streamingText: '',
    usedFallback: false,
    explanationMode: null,
    toolProvenance: null,
    orchestrationMode: null,
    plannerUsed: null,
    toolTrace: null,
    plannerFallbackReason: null,
    error: null,
    sessionId: null,
    lastIntent: null,
    strategyMetadata: null,
  });

  const [history, setHistory] = useState<ConversationEntry[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  // Refs to avoid stale closures in submit
  const isStreamingRef = useRef(false);
  const jwtTokenRef = useRef<string | null | undefined>(jwtToken);
  const refreshTokenRef = useRef<(() => Promise<boolean>) | undefined>(refreshToken);
  const apiKeyRef = useRef<string | null | undefined>(apiKey);
  const userIdRef = useRef<string | null | undefined>(userId);
  const saveMessageRef = useRef<((params: SaveMessageParams) => Promise<boolean>) | undefined>(saveMessage);
  const conversationIdRef = useRef<string | null>(null);
  const pendingSaveRef = useRef<Promise<boolean> | null>(null);

  // Keep refs in sync with props
  useEffect(() => {
    jwtTokenRef.current = jwtToken;
  }, [jwtToken]);

  useEffect(() => {
    refreshTokenRef.current = refreshToken;
  }, [refreshToken]);

  useEffect(() => {
    apiKeyRef.current = apiKey;
  }, [apiKey]);

  useEffect(() => {
    userIdRef.current = userId;
  }, [userId]);

  useEffect(() => {
    saveMessageRef.current = saveMessage;
  }, [saveMessage]);

  // Keep isStreamingRef in sync
  const setStateRef = (updater: StreamState | ((s: StreamState) => StreamState)) => {
    setState(prev => {
      const next = typeof updater === 'function' ? updater(prev) : updater;
      isStreamingRef.current = next.isStreaming;
      return next;
    });
  };

  const { isStreaming, decodeResult, siconiaAnalysis, streamingText, usedFallback, error, sessionId, lastIntent } = state;

  const submit = useCallback(async (
    userInput: string,
    existingSessionId?: string,
    conversationId?: string,
    suggestedEndpoint?: 'decode' | 'siconia',
    artifacts?: WorkflowArtifactInput[],
  ): Promise<boolean> => {
    if (!apiKeyRef.current && !jwtTokenRef.current) {
      setStateRef(s => ({ ...s, error: 'Authentication required. Please login or set an API key.' }));
      return false;
    }

    // Track conversation ID for saving messages
    conversationIdRef.current = conversationId || null;
    pendingSaveRef.current = null;

    // Abort any in-flight request
    if (isStreamingRef.current) {
      abortRef.current?.abort();
    }

    const inputClass = detectInputClass(userInput);
    const normalizedRequestInput = inputClass === 'log_block'
      ? normalizeWrappedLogInput(userInput)
      : userInput;
    void suggestedEndpoint;
    const endpoint = '/api/chat/stream';

    const controller = new AbortController();
    abortRef.current = controller;

    setStateRef({
      isStreaming: true,
      decodeResult: null,
      siconiaAnalysis: null,
      artifactResults: null,
      streamingText: '',
      usedFallback: false,
      explanationMode: null,
      toolProvenance: null,
      orchestrationMode: null,
      plannerUsed: null,
      toolTrace: null,
      plannerFallbackReason: null,
      error: null,
      sessionId: existingSessionId || null,
      lastIntent: null,
      strategyMetadata: null,
    });

    const finalizeStructuredEntry = async (
      context: FinalizedStreamContext,
      finalizedRef: { current: boolean }
    ) => {
      if (finalizedRef.current) {
        return;
      }

      const structuredResult: StructuredMessagePayload | null =
        context.finalSiconiaAnalysisRef.current || context.finalDecodeResultRef.current || null;
      const artifactResults = context.artifactResultsRef.current;
      if (!context.fullTextRef.current && !structuredResult && (!artifactResults || artifactResults.length === 0)) {
        finalizedRef.current = true;
        return;
      }

      finalizedRef.current = true;

      const entry: ConversationEntry = {
        id: crypto.randomUUID(),
        timestamp: new Date(),
        inputClass: context.inputClass,
        userInput: context.userInput,
        decodeResult: context.finalDecodeResultRef.current,
        siconiaAnalysis: context.finalSiconiaAnalysisRef.current,
        artifactResults,
        explanation: context.fullTextRef.current,
        sessionId: context.finalSessionIdRef.current || '',
        usedFallback: context.usedFallbackRef.current,
        explanationMode: context.explanationModeRef.current,
        toolProvenance: context.toolProvenanceRef.current,
        orchestrationMode: context.orchestrationModeRef.current,
        plannerUsed: context.plannerUsedRef.current,
        toolTrace: context.toolTraceRef.current,
        plannerFallbackReason: context.plannerFallbackReasonRef.current,
        intent: context.finalIntentRef.current || undefined,
        strategyMetadata: context.finalStrategyMetadataRef.current,
      };
      setHistory((h) => [...h, entry]);

      const convId = conversationIdRef.current;
      if (convId && saveMessageRef.current) {
        pendingSaveRef.current = saveMessageRef.current({
          convId,
          role: 'assistant',
          rawInput: context.userInput,
          inputClass: context.inputClass,
          intent: context.finalIntentRef.current || null,
          explanation: context.fullTextRef.current,
          sessionId: context.finalSessionIdRef.current || '',
          structuredResult,
          artifactResults,
          strategyMetadata: context.finalStrategyMetadataRef.current,
          usedFallback: context.usedFallbackRef.current,
          explanationMode: context.explanationModeRef.current,
          toolProvenance: context.toolProvenanceRef.current,
          orchestrationMode: context.orchestrationModeRef.current,
          plannerUsed: context.plannerUsedRef.current,
          toolTrace: context.toolTraceRef.current,
          plannerFallbackReason: context.plannerFallbackReasonRef.current,
        }).catch(() => false);
      }

      if (pendingSaveRef.current) {
        await pendingSaveRef.current;
        pendingSaveRef.current = null;
      }
    };

    // Inner handler for SSE lines to avoid closure issues
    const handleLine = (
      line: string,
      currentEventTypeRef: { current: string },
      context: FinalizedStreamContext & {
        doneSeenRef: { current: boolean };
      }
    ) => {
      const trimmed = line.trim();
      if (!trimmed) return;
      // SSE spec: "event:type" or "event: type" — handle both
      if (trimmed.startsWith('event:')) {
        // Extract event type after "event:" (with or without space)
        currentEventTypeRef.current = trimmed.slice(6).trim();
        return;
      }
      // SSE spec: "data:content" or "data: content" — handle both
      if (!trimmed.startsWith('data:')) return;

      try {
        const data = JSON.parse(trimmed.slice(5).trim());
        const eventType = currentEventTypeRef.current;
        const eventUsedFallback = getEventBoolean(data, 'usedFallback');
        const eventExplanationMode = getEventString(data, 'explanationMode') as JavaExplanationMode | null;
        const eventToolProvenance = getEventString(data, 'toolProvenance') as JavaToolProvenance | null;
        const eventOrchestrationMode = getEventString(data, 'orchestrationMode') as JavaOrchestrationMode | null;
        const eventPlannerUsed = getEventBoolean(data, 'plannerUsed');
        const eventToolTrace = getEventArray<JavaToolTraceEntry>(data, 'toolTrace');
        const eventPlannerFallbackReason = getEventString(data, 'plannerFallbackReason');
        const eventPayload = getEventPayload(data);

        if (eventType === 'decode') {
          // The 'decode' event now carries full decodeResult + siconiaResult in the payload
          context.finalSessionIdRef.current = getEventString(data, 'sessionId');
          if (eventUsedFallback !== null) {
            context.usedFallbackRef.current = eventUsedFallback;
          }
          if (eventExplanationMode !== null) {
            context.explanationModeRef.current = eventExplanationMode;
          }
          if (eventToolProvenance !== null) {
            context.toolProvenanceRef.current = eventToolProvenance;
          }
          if (eventOrchestrationMode !== null) {
            context.orchestrationModeRef.current = eventOrchestrationMode;
          }
          if (eventPlannerUsed !== null) {
            context.plannerUsedRef.current = eventPlannerUsed;
          }
          if (eventToolTrace !== null) {
            context.toolTraceRef.current = eventToolTrace;
          }
          if (eventPlannerFallbackReason !== null) {
            context.plannerFallbackReasonRef.current = eventPlannerFallbackReason;
          }
          const decodeResult = getEventObject<DecodeResult | JavaDecodeResult>(data, 'decodeResult');
          if (decodeResult) {
            context.finalDecodeResultRef.current = decodeResult;
          }
          const siconiaResult = getEventObject<SiconiaAnalysis | JavaSiconiaResult>(data, 'siconiaResult');
          if (siconiaResult) {
            context.finalSiconiaAnalysisRef.current = siconiaResult;
          }
          const artifactResults = getEventArray<JavaArtifactResult>(data, 'artifactResults');
          if (artifactResults) {
            context.artifactResultsRef.current = artifactResults;
          }
          const intent = getEventString(data, 'intent');
          if (intent) {
            context.finalIntentRef.current = intent;
          }
          const strategyMetadata = getEventObject<JavaStrategyMetadata>(data, 'strategyMetadata');
          if (strategyMetadata) {
            context.finalStrategyMetadataRef.current = strategyMetadata;
          }
          setStateRef(s => ({
            ...s,
            sessionId: getEventString(data, 'sessionId'),
            decodeResult: decodeResult || s.decodeResult,
            siconiaAnalysis: siconiaResult || s.siconiaAnalysis,
            artifactResults: artifactResults || s.artifactResults,
            usedFallback: eventUsedFallback ?? s.usedFallback,
            explanationMode: eventExplanationMode ?? s.explanationMode,
            toolProvenance: eventToolProvenance ?? s.toolProvenance,
            orchestrationMode: eventOrchestrationMode ?? s.orchestrationMode,
            plannerUsed: eventPlannerUsed ?? s.plannerUsed,
            toolTrace: eventToolTrace ?? s.toolTrace,
            plannerFallbackReason: eventPlannerFallbackReason ?? s.plannerFallbackReason,
            lastIntent: intent || s.lastIntent,
            strategyMetadata: strategyMetadata || s.strategyMetadata,
          }));
        } else if (eventType === 'analysis') {
          // Backend may send either {siconiaResult:{...}} or direct {...} payload.
          // Only treat it as SiconiaResult if it actually has analysis fields.
          const directLooksLikeSiconia =
            eventPayload && (
              Array.isArray(eventPayload.alarmResults)
              || typeof eventPayload.logAnalysis === 'object'
              || typeof eventPayload.xmlTrace === 'object'
              || typeof eventPayload.processingMetadata === 'object'
            );
          const siconiaData =
            getEventObject<SiconiaAnalysis | JavaSiconiaResult>(data, 'siconiaResult')
            || (directLooksLikeSiconia ? eventPayload as unknown as SiconiaAnalysis | JavaSiconiaResult : null);
          context.finalSiconiaAnalysisRef.current = siconiaData;
          const artifactResults = getEventArray<JavaArtifactResult>(data, 'artifactResults');
          if (artifactResults) {
            context.artifactResultsRef.current = artifactResults;
          }
          context.finalSessionIdRef.current = getEventString(data, 'sessionId');
          const intent = getEventString(data, 'intent');
          if (intent) {
            context.finalIntentRef.current = intent;
          }
          const strategyMetadata = getEventObject<JavaStrategyMetadata>(data, 'strategyMetadata');
          if (strategyMetadata) {
            context.finalStrategyMetadataRef.current = strategyMetadata;
          }
          if (eventUsedFallback !== null) {
            context.usedFallbackRef.current = eventUsedFallback;
          }
          if (eventExplanationMode !== null) {
            context.explanationModeRef.current = eventExplanationMode;
          }
          if (eventToolProvenance !== null) {
            context.toolProvenanceRef.current = eventToolProvenance;
          }
          if (eventOrchestrationMode !== null) {
            context.orchestrationModeRef.current = eventOrchestrationMode;
          }
          if (eventPlannerUsed !== null) {
            context.plannerUsedRef.current = eventPlannerUsed;
          }
          if (eventToolTrace !== null) {
            context.toolTraceRef.current = eventToolTrace;
          }
          if (eventPlannerFallbackReason !== null) {
            context.plannerFallbackReasonRef.current = eventPlannerFallbackReason;
          }
          setStateRef(s => ({
            ...s,
            siconiaAnalysis: siconiaData || s.siconiaAnalysis,
            artifactResults: artifactResults || s.artifactResults,
            sessionId: getEventString(data, 'sessionId'),
            usedFallback: eventUsedFallback ?? s.usedFallback,
            explanationMode: eventExplanationMode ?? s.explanationMode,
            toolProvenance: eventToolProvenance ?? s.toolProvenance,
            orchestrationMode: eventOrchestrationMode ?? s.orchestrationMode,
            plannerUsed: eventPlannerUsed ?? s.plannerUsed,
            toolTrace: eventToolTrace ?? s.toolTrace,
            plannerFallbackReason: eventPlannerFallbackReason ?? s.plannerFallbackReason,
            lastIntent: intent || s.lastIntent,
            strategyMetadata: strategyMetadata || s.strategyMetadata,
          }));
        } else if (eventType === 'token') {
          const token = data.t || '';
          context.fullTextRef.current += token;
          context.enqueueDisplayToken(token);
        } else if (eventType === 'filtered') {
          setStateRef(s => ({ ...s, error: data.reason || 'Content filtered' }));
        } else if (eventType === 'done') {
          context.doneSeenRef.current = true;
        }
      } catch (e) {
        void e;
      }
    };

    // Helper to perform the actual fetch and process SSE stream
    const doFetch = async (token: string | null | undefined): Promise<boolean> => {
      // Prefer JWT Bearer (UI login); fall back to API key (direct access)
      const authHeader: Record<string, string> = token
        ? { Authorization: `Bearer ${token}` }
        : { 'X-API-Key': apiKeyRef.current || '' };

      const res = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...authHeader,
        },
        body: JSON.stringify({
              rawInput: normalizedRequestInput,
              sessionId: existingSessionId || undefined,
              conversationId: conversationId || undefined,
              userId: userIdRef.current || undefined,
              inputClass: 'QUERY',
              artifacts: artifacts && artifacts.length > 0 ? artifacts : undefined,
        }),
        signal: controller.signal,
      });

      if (!res.ok) {
        if (res.status === 401 && refreshTokenRef.current) {
          const refreshed = await refreshTokenRef.current();
          if (!refreshed) {
            setStateRef(s => ({ ...s, isStreaming: false, error: 'Session expired. Please sign in again.' }));
            return false;
          }
          // Read fresh token from localStorage — React ref lags a render cycle
          const newToken = localStorage.getItem('dlms_access_token');
          if (!newToken) {
            setStateRef(s => ({ ...s, isStreaming: false, error: 'Session expired. Please sign in again.' }));
            return false;
          }
          // Retry with the new token
          return doFetch(newToken);
        }
        const message =
          res.status === 401
            ? 'Unauthorized. Please sign in again.'
          : res.status === 403
            ? 'Insufficient permissions for this operation.'
            : await readApiError(res, `HTTP ${res.status}`);
        setStateRef(s => ({ ...s, isStreaming: false, error: message }));
        return false;
      }

      if (!res.body) {
        setStateRef(s => ({ ...s, isStreaming: false, error: 'Empty response body' }));
        return false;
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      const finalDecodeResultRef = { current: null as DecodeResult | JavaDecodeResult | null };
      const finalSiconiaAnalysisRef = { current: null as SiconiaAnalysis | JavaSiconiaResult | null };
      const artifactResultsRef = { current: null as JavaArtifactResult[] | null };
      const fullTextRef = { current: '' };
      const finalSessionIdRef = { current: existingSessionId || null };
      const finalIntentRef = { current: null as string | null };
      const finalStrategyMetadataRef = { current: null as JavaStrategyMetadata | null };
      const orchestrationModeRef = { current: null as JavaOrchestrationMode | null };
      const plannerUsedRef = { current: null as boolean | null };
      const toolTraceRef = { current: null as JavaToolTraceEntry[] | null };
      const plannerFallbackReasonRef = { current: null as string | null };
      const currentEventTypeRef = { current: '' };
      const usedFallbackRef = { current: false };
      const explanationModeRef = { current: null as JavaExplanationMode | null };
      const toolProvenanceRef = { current: null as JavaToolProvenance | null };
      const doneSeenRef = { current: false };
      const finalizedRef = { current: false };
      const displayQueueRef = { current: [] as string[] };
      const displayDrainPromiseRef = { current: null as Promise<void> | null };
      const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
      const drainDisplayQueue = async (): Promise<void> => {
        while (displayQueueRef.current.length > 0) {
          const chunk = displayQueueRef.current.shift() || '';
          if (!chunk) continue;
          setStateRef((s) => ({ ...s, streamingText: s.streamingText + chunk }));
          if (STREAM_DISPLAY_DELAY_MS > 0 && /\S/.test(chunk)) {
            await sleep(STREAM_DISPLAY_DELAY_MS);
          }
        }
      };
      const enqueueDisplayToken = (token: string) => {
        if (!token) return;
        displayQueueRef.current.push(...splitStreamDisplayChunks(token));
        if (!displayDrainPromiseRef.current) {
          displayDrainPromiseRef.current = (async () => {
            try {
              await drainDisplayQueue();
            } finally {
              displayDrainPromiseRef.current = null;
            }
          })();
        }
      };
      const flushDisplayQueue = async () => {
        while (displayDrainPromiseRef.current) {
          await displayDrainPromiseRef.current;
        }
      };
      const finalizeContext: FinalizedStreamContext = {
        inputClass,
        userInput,
        finalDecodeResultRef,
        finalSiconiaAnalysisRef,
        artifactResultsRef,
        finalSessionIdRef,
        finalIntentRef,
        finalStrategyMetadataRef,
        orchestrationModeRef,
        plannerUsedRef,
        toolTraceRef,
        plannerFallbackReasonRef,
        fullTextRef,
        usedFallbackRef,
        explanationModeRef,
        toolProvenanceRef,
        enqueueDisplayToken,
        flushDisplayQueue,
      };

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          handleLine(
            line,
            currentEventTypeRef,
            {
              ...finalizeContext,
              doneSeenRef,
            }
          );
        }
      }

      // Process any trailing buffered content
      if (buffer.trim()) {
        handleLine(
          buffer,
          currentEventTypeRef,
          {
            ...finalizeContext,
            doneSeenRef,
          }
        );
      }

      await finalizeContext.flushDisplayQueue();
      await finalizeStructuredEntry(finalizeContext, finalizedRef);

      // Only mark streaming complete after the final entry has been committed and
      // any persistence callback has resolved, so refreshes cannot race the save.
      setStateRef(s => ({ ...s, isStreaming: false }));
      return true;
    };

    try {
      return await doFetch(jwtTokenRef.current);
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        // Only clear if this was the current controller
        if (abortRef.current === controller) {
           setStateRef(s => ({ ...s, isStreaming: false }));
        }
        return false;
      }
      setStateRef(s => ({
        ...s,
        isStreaming: false,
        error: err instanceof Error ? err.message : 'Network error',
      }));
      return false;
    }
  }, []); // No deps — all external values accessed via refs

  const reset = useCallback(() => {
    abortRef.current?.abort();
    setStateRef({
      isStreaming: false,
      decodeResult: null,
      siconiaAnalysis: null,
      artifactResults: null,
      streamingText: '',
      usedFallback: false,
      explanationMode: null,
      toolProvenance: null,
      orchestrationMode: null,
      plannerUsed: null,
      toolTrace: null,
      plannerFallbackReason: null,
      error: null,
      sessionId: null,
      lastIntent: null,
      strategyMetadata: null,
    });
  }, []);

  const clearHistory = useCallback(() => {
    setHistory([]);
  }, []);

  return {
    isStreaming,
    decodeResult,
    siconiaAnalysis,
    artifactResults: state.artifactResults,
    streamingText,
    usedFallback,
    explanationMode: state.explanationMode,
    toolProvenance: state.toolProvenance,
    orchestrationMode: state.orchestrationMode,
    plannerUsed: state.plannerUsed,
    toolTrace: state.toolTrace,
    plannerFallbackReason: state.plannerFallbackReason,
    error,
    sessionId,
    lastIntent,
    strategyMetadata: state.strategyMetadata,
    history,
    setHistory,
    submit,
    reset,
    clearHistory,
    detectInputClass,
  };
}
