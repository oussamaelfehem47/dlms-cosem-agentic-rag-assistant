/**
 * useConversations — CRUD hook for conversation persistence.
 */
import { useState, useCallback, useEffect } from 'react';
import {
  JavaArtifactResult,
  JavaExplanationMode,
  JavaOrchestrationMode,
  JavaStrategyMetadata,
  JavaToolTraceEntry,
  JavaToolProvenance,
  StructuredMessagePayload,
} from '../types';

export interface Conversation {
  id: string;
  title: string;
  created_at: string;
  updated_at: string;
  message_count: number;
}

export interface Message {
  id: string;
  role: string;
  input_class: string | null;
  intent: string | null;
  raw_input: string | null;
  session_id?: string | null;
  decode_result: StructuredMessagePayload | null;
  artifact_results?: JavaArtifactResult[] | null;
  strategy_metadata?: JavaStrategyMetadata | null;
  orchestration_mode?: JavaOrchestrationMode | null;
  planner_used?: boolean | null;
  tool_trace?: JavaToolTraceEntry[] | null;
  planner_fallback_reason?: string | null;
  explanation: string | null;
  used_mcp_fallback: boolean;
  explanation_mode?: JavaExplanationMode | null;
  tool_provenance?: JavaToolProvenance | null;
  created_at: string;
}

export interface SaveMessageParams {
  convId: string;
  role: string;
  rawInput: string;
  inputClass: string;
  intent?: string | null;
  explanation: string;
  sessionId: string;
  structuredResult?: StructuredMessagePayload | null;
  artifactResults?: JavaArtifactResult[] | null;
  strategyMetadata?: JavaStrategyMetadata | null;
  usedFallback?: boolean;
  explanationMode?: JavaExplanationMode | null;
  toolProvenance?: JavaToolProvenance | null;
  orchestrationMode?: JavaOrchestrationMode | null;
  plannerUsed?: boolean | null;
  toolTrace?: JavaToolTraceEntry[] | null;
  plannerFallbackReason?: string | null;
}

function sortConversationsByUpdatedAtDesc(items: Conversation[]): Conversation[] {
  return [...items].sort((left, right) =>
    new Date(right.updated_at).getTime() - new Date(left.updated_at).getTime()
  );
}

export function useConversations(token: string | null) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConv, setActiveConv] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const API =
    (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_URL || '/api';

  const authHeaders = useCallback(
    (): HeadersInit => ({
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    }),
    [token]
  );

  const fetchConversations = useCallback(async () => {
    if (!token) return;
    try {
      const res = await fetch(`${API}/conversations`, {
        headers: authHeaders(),
      });
      if (res.ok) {
        const data: Conversation[] = await res.json();
        setConversations(sortConversationsByUpdatedAtDesc(data));
      }
    } catch {
      // Conversations are optional; leave UI usable if this request fails.
    }
  }, [API, authHeaders, token]);

  // Load conversations on mount / token change
  useEffect(() => {
    if (token) {
      void fetchConversations();
    }
  }, [token, fetchConversations]);

  const createConversation = useCallback(async (
    title = 'New Conversation'
  ): Promise<string | null> => {
    if (!token) return null;
    try {
      const res = await fetch(`${API}/conversations`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ title }),
      });
      if (res.ok) {
        const conv: Conversation = await res.json();
        setConversations((prev) => sortConversationsByUpdatedAtDesc([...prev, conv]));
        setActiveConv(conv);
        setMessages([]);
        return conv.id;
      }
    } catch {
      return null;
    }
    return null;
  }, [API, authHeaders, token]);

  const openConversation = useCallback(async (convId: string): Promise<(Conversation & { messages: Message[] }) | null> => {
    if (!token || !convId) {
      setActiveConv(null);
      setMessages([]);
      return null;
    }
    setIsLoading(true);
    try {
      const res = await fetch(`${API}/conversations/${convId}`, {
        headers: authHeaders(),
      });
      if (res.ok) {
        const data: Conversation & { messages: Message[] } = await res.json();
        setActiveConv(data);
        setMessages(data.messages);
        return data;
      }
    } catch {
      return null;
    } finally {
      setIsLoading(false);
    }
    return null;
  }, [API, authHeaders, token]);

  /**
   * Save a message to the backend for a given conversation.
   * Uses camelCase field names matching the backend SaveMessageRequest record.
   * Returns true on success.
   */
  const saveMessage = useCallback(async ({
    convId,
    role,
    rawInput,
    inputClass,
    intent = null,
    explanation,
    sessionId,
    structuredResult = null,
    artifactResults = null,
    strategyMetadata = null,
    usedFallback = false,
    explanationMode = null,
    toolProvenance = null,
    orchestrationMode = null,
    plannerUsed = null,
    toolTrace = null,
    plannerFallbackReason = null,
  }: SaveMessageParams): Promise<boolean> => {
    if (!token) return false;
    try {
      const res = await fetch(`${API}/conversations/${convId}/messages`, {
        method: 'POST',
        keepalive: true,
        headers: authHeaders(),
        body: JSON.stringify({
          role,
          rawInput,
          inputClass,
          intent,
          decodeResultJson: structuredResult ? JSON.stringify(structuredResult) : null,
          artifactResultsJson: artifactResults ? JSON.stringify(artifactResults) : null,
          strategyMetadataJson: strategyMetadata ? JSON.stringify(strategyMetadata) : null,
          orchestrationMode,
          plannerUsed,
          toolTraceJson: toolTrace ? JSON.stringify(toolTrace) : null,
          plannerFallbackReason,
          explanation,
          sessionId,
          usedMcpFallback: usedFallback,
          explanationMode,
          toolProvenance,
        }),
      });
      if (res.ok) {
        const nextUpdatedAt = new Date().toISOString();
        setConversations((prev) =>
          sortConversationsByUpdatedAtDesc(
            prev.map((conversation) =>
              conversation.id === convId
                ? {
                    ...conversation,
                    message_count: conversation.message_count + 1,
                    updated_at: nextUpdatedAt,
                  }
                : conversation
            )
          )
        );
        setActiveConv((prev) =>
          prev && prev.id === convId
            ? {
                ...prev,
                message_count: prev.message_count + 1,
                updated_at: nextUpdatedAt,
              }
            : prev
        );
        // Refresh messages after saving
        void openConversation(convId);
        return true;
      }
    } catch {
      return false;
    }
    return false;
  }, [API, authHeaders, openConversation, token]);

  const renameConversation = useCallback(async (convId: string, title: string): Promise<boolean> => {
    if (!token) return false;
    try {
      const res = await fetch(`${API}/conversations/${convId}/title`, {
        method: 'PATCH',
        headers: authHeaders(),
        body: JSON.stringify({ title }),
      });
      if (res.ok) {
        const updated = await res.json();
        setConversations(prev =>
          sortConversationsByUpdatedAtDesc(
            prev.map(c => c.id === convId ? { ...c, title: updated.title } : c)
          )
        );
        if (activeConv?.id === convId) setActiveConv(prev => prev ? { ...prev, title: updated.title } : prev);
        return true;
      }
    } catch {
      return false;
    }
    return false;
  }, [API, activeConv?.id, authHeaders, token]);

  const deleteConversation = useCallback(async (convId: string): Promise<boolean> => {
    if (!token) return false;
    try {
      const res = await fetch(`${API}/conversations/${convId}`, {
        method: 'DELETE',
        headers: authHeaders(),
      });
      if (res.ok) {
        setConversations((prev) => prev.filter((c) => c.id !== convId));
        if (activeConv?.id === convId) {
          setActiveConv(null);
          setMessages([]);
        }
        return true;
      }
    } catch {
      // Keep current UI state if deletion fails.
    }
    return false;
  }, [API, activeConv?.id, authHeaders, token]);

  return {
    conversations,
    activeConv,
    messages,
    isLoading,
    fetchConversations,
    createConversation,
    openConversation,
    saveMessage,
    renameConversation,
    deleteConversation,
    setActiveConv,
  };
}
