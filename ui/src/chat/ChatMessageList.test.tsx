import React from 'react';
import { render, screen } from '@testing-library/react';
import { ChatMessageList } from './ChatMessageList';
import { ConversationEntry } from '../types';

function renderList(history: ConversationEntry[]) {
  return render(
    <ChatMessageList
      history={history}
      isStreaming={false}
      streamingInput=""
      streamingInputClass="query"
      streamingText=""
      streamingIntent={null}
      usedFallback={false}
      messagesEndRef={React.createRef<HTMLDivElement>()}
    />
  );
}

describe('ChatMessageList', () => {
  it('uses persisted documentation intent instead of the old decode heuristic', () => {
    renderList([
      {
        id: 'entry-1',
        timestamp: new Date('2026-05-06T08:00:00.000Z'),
        inputClass: 'query',
        userInput: 'Explain HDLC frame structure',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'HDLC frames carry link-layer control and payload fields.',
        sessionId: 'sess-1',
        usedFallback: false,
        intent: 'DOCUMENTATION',
      },
    ]);

    expect(screen.queryByText(/^decode$/i)).not.toBeInTheDocument();
  });

  it('falls back to the query heuristic for legacy messages without intent', () => {
    renderList([
      {
        id: 'entry-2',
        timestamp: new Date('2026-05-06T08:00:00.000Z'),
        inputClass: 'query',
        userInput: 'Explain HDLC frame structure',
        decodeResult: null,
        siconiaAnalysis: null,
        explanation: 'HDLC frames carry link-layer control and payload fields.',
        sessionId: 'sess-2',
        usedFallback: false,
      },
    ]);

    expect(screen.getByText(/^decode$/i)).toBeInTheDocument();
  });
});
