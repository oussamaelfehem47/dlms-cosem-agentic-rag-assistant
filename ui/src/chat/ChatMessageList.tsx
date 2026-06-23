import React from 'react';
import { AssistantMessage, UserMessage } from '../components/ChatMessage';
import {
  ConversationEntry,
  JavaArtifactResult,
  InputClass,
  JavaExplanationMode,
  JavaOrchestrationMode,
  JavaStrategyMetadata,
  JavaToolTraceEntry,
  JavaToolProvenance,
} from '../types';
import { resolveConversationCategory } from './chatFeatureUtils';

interface Props {
  history: ConversationEntry[];
  isStreaming: boolean;
  streamingInput: string;
  streamingInputClass: InputClass;
  streamingText: string;
  streamingArtifactResults?: JavaArtifactResult[] | null;
  streamingIntent?: string | null;
  streamingStrategyMetadata?: JavaStrategyMetadata | null;
  usedFallback: boolean;
  streamingExplanationMode?: JavaExplanationMode | null;
  streamingToolProvenance?: JavaToolProvenance | null;
  streamingOrchestrationMode?: JavaOrchestrationMode | null;
  streamingPlannerUsed?: boolean | null;
  streamingToolTrace?: JavaToolTraceEntry[] | null;
  streamingPlannerFallbackReason?: string | null;
  messagesEndRef: React.RefObject<HTMLDivElement | null>;
  onFeedback?: (entryId: string, value: 'like' | 'dislike') => void;
}

export const ChatMessageList: React.FC<Props> = ({
  history,
  isStreaming,
  streamingInput,
  streamingInputClass,
  streamingText,
  streamingArtifactResults,
  streamingIntent,
  streamingStrategyMetadata,
  usedFallback,
  streamingExplanationMode,
  streamingToolProvenance,
  streamingOrchestrationMode,
  streamingPlannerUsed,
  streamingToolTrace,
  streamingPlannerFallbackReason,
  messagesEndRef,
  onFeedback,
}) => {
  return (
    <div
      data-testid="chat-message-list"
      style={{ padding: '20px 16px 220px', maxWidth: 860, margin: '0 auto' }}
    >
      {history.map((entry) => (
        <div key={entry.id}>
          <UserMessage
            text={entry.userInput}
            inputClass={entry.inputClass}
            timestamp={entry.timestamp}
            decodeResult={entry.decodeResult}
            siconiaAnalysis={entry.siconiaAnalysis}
          />
          <AssistantMessage
            text={entry.explanation}
            decodeResult={entry.decodeResult}
            siconiaAnalysis={entry.siconiaAnalysis}
            artifactResults={entry.artifactResults}
            isStreaming={false}
            usedFallback={entry.usedFallback}
            explanationMode={entry.explanationMode}
            toolProvenance={entry.toolProvenance}
            orchestrationMode={entry.orchestrationMode}
            plannerUsed={entry.plannerUsed}
            toolTrace={entry.toolTrace}
            plannerFallbackReason={entry.plannerFallbackReason}
            timestamp={entry.timestamp}
            uiCategory={resolveConversationCategory(entry.userInput, entry.inputClass, entry.intent)}
            strategyMetadata={entry.strategyMetadata}
            onFeedback={onFeedback ? (v) => onFeedback(entry.id, v) : undefined}
          />
        </div>
      ))}

      {isStreaming && (
        <div>
          <UserMessage
            text={streamingInput}
            inputClass={streamingInputClass}
            timestamp={new Date()}
            decodeResult={null}
            siconiaAnalysis={null}
          />
          <AssistantMessage
            text={streamingText}
            decodeResult={null}
            siconiaAnalysis={null}
            artifactResults={streamingArtifactResults}
            isStreaming={true}
            usedFallback={usedFallback}
            explanationMode={streamingExplanationMode}
            toolProvenance={streamingToolProvenance}
            orchestrationMode={streamingOrchestrationMode}
            plannerUsed={streamingPlannerUsed}
            toolTrace={streamingToolTrace}
            plannerFallbackReason={streamingPlannerFallbackReason}
            uiCategory={resolveConversationCategory(streamingInput, streamingInputClass, streamingIntent)}
            strategyMetadata={streamingStrategyMetadata}
          />
        </div>
      )}

      <div ref={messagesEndRef} />
    </div>
  );
};
