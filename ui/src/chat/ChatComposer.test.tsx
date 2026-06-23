import React, { useRef, useState } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatComposer } from './ChatComposer';
import { UploadResult } from '../components/UploadButton';

type HarnessProps = {
  isStreaming?: boolean;
  initialUploading?: boolean;
};

function createFileDataTransfer(files: File[]): DataTransfer {
  return {
    files,
    items: files.map((file) => ({
      kind: 'file',
      type: file.type,
      getAsFile: () => file,
    })),
    types: ['Files'],
    dropEffect: 'none',
  } as unknown as DataTransfer;
}

function createTextDataTransfer(): DataTransfer {
  return {
    files: [],
    items: [{
      kind: 'string',
      type: 'text/plain',
      getAsFile: () => null,
    }],
    types: ['text/plain'],
    dropEffect: 'none',
  } as unknown as DataTransfer;
}

function ComposerHarness({ isStreaming = false, initialUploading = false }: HarnessProps) {
  const [attachments, setAttachments] = useState<UploadResult[]>([]);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(initialUploading);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  return (
    <ChatComposer
      token="token"
      apiKey={null}
      isStreaming={isStreaming}
      inputText=""
      attachments={attachments}
      attachmentResetSignal={0}
      uploadError={uploadError}
      streamError={null}
      uploading={uploading}
      showSamples={false}
      canSend={attachments.length > 0}
      activePreset={null}
      composerCategory="general"
      textareaRef={textareaRef}
      onInputChange={() => {}}
      onKeyDown={() => {}}
      onToggleSamples={() => {}}
      onSampleSelect={() => {}}
      onPresetSelect={() => {}}
      onAttachmentResult={(result) => {
        setAttachments((current) => [...current, result]);
        setUploadError(null);
      }}
      onUploadError={setUploadError}
      setUploading={setUploading}
      onRemoveAttachment={(index) => {
        setAttachments((current) => current.filter((_, currentIndex) => currentIndex !== index));
      }}
      onMoveAttachment={(index, direction) => {
        setAttachments((current) => {
          const nextIndex = direction === 'up' ? index - 1 : index + 1;
          if (nextIndex < 0 || nextIndex >= current.length) return current;
          const next = [...current];
          const [item] = next.splice(index, 1);
          next.splice(nextIndex, 0, item);
          return next;
        });
      }}
      onSubmit={() => {}}
    />
  );
}

describe('ChatComposer drag and drop', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (_input, init) => {
      const formData = init?.body as FormData;
      const file = formData.get('file') as File;
      return new Response(JSON.stringify({
        text: `content for ${file.name}`,
        input_class: 'query',
        type: 'file',
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('activates the subtle drag state for file drags', () => {
    render(<ComposerHarness />);

    const dropTarget = screen.getByTestId('composer-drop-target');
    fireEvent.dragEnter(dropTarget, {
      dataTransfer: createFileDataTransfer([new File(['a'], 'frame.txt', { type: 'text/plain' })]),
    });

    expect(dropTarget).toHaveAttribute('data-drag-active', 'true');
  });

  it('clears the drag state after drag leave', () => {
    render(<ComposerHarness />);

    const transfer = createFileDataTransfer([new File(['a'], 'frame.txt', { type: 'text/plain' })]);
    const dropTarget = screen.getByTestId('composer-drop-target');

    fireEvent.dragEnter(dropTarget, { dataTransfer: transfer });
    expect(dropTarget).toHaveAttribute('data-drag-active', 'true');

    fireEvent.dragLeave(dropTarget, { dataTransfer: transfer });
    expect(dropTarget).toHaveAttribute('data-drag-active', 'false');
  });

  it('ignores non-file drags', () => {
    render(<ComposerHarness />);

    const dropTarget = screen.getByTestId('composer-drop-target');
    fireEvent.dragEnter(dropTarget, { dataTransfer: createTextDataTransfer() });

    expect(dropTarget).toHaveAttribute('data-drag-active', 'false');
  });

  it('drops a file through the shared upload path and adds it to the queue', async () => {
    render(<ComposerHarness />);

    const dropTarget = screen.getByTestId('composer-drop-target');
    const file = new File(['7EA00A030383CD6F7E'], 'frame-drop.txt', { type: 'text/plain' });
    const transfer = createFileDataTransfer([file]);

    fireEvent.dragEnter(dropTarget, { dataTransfer: transfer });
    fireEvent.dragOver(dropTarget, { dataTransfer: transfer });
    fireEvent.drop(dropTarget, { dataTransfer: transfer });

    await waitFor(() => expect(screen.getByTestId('attachment-queue')).toBeVisible());
    expect(await screen.findByText('frame-drop.txt')).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(dropTarget).toHaveAttribute('data-drag-active', 'false');
  });

  it('preserves dropped file order in the queue', async () => {
    render(<ComposerHarness />);

    const dropTarget = screen.getByTestId('composer-drop-target');
    const first = new File(['first'], 'drop-one.txt', { type: 'text/plain' });
    const second = new File(['second'], 'drop-two.txt', { type: 'text/plain' });
    const transfer = createFileDataTransfer([first, second]);

    fireEvent.drop(dropTarget, { dataTransfer: transfer });

    const queue = await screen.findByTestId('attachment-queue');
    await waitFor(() => {
      expect(within(queue).getByText('drop-one.txt')).toBeInTheDocument();
      expect(within(queue).getByText('drop-two.txt')).toBeInTheDocument();
    });

    const queueText = queue.textContent || '';
    expect(queueText.indexOf('drop-one.txt')).toBeLessThan(queueText.indexOf('drop-two.txt'));
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('ignores drops while streaming is active', async () => {
    render(<ComposerHarness isStreaming />);

    const dropTarget = screen.getByTestId('composer-drop-target');
    const transfer = createFileDataTransfer([new File(['busy'], 'blocked.txt', { type: 'text/plain' })]);

    fireEvent.dragEnter(dropTarget, { dataTransfer: transfer });
    fireEvent.drop(dropTarget, { dataTransfer: transfer });

    expect(dropTarget).toHaveAttribute('data-drag-active', 'false');
    expect(fetch).not.toHaveBeenCalled();
    await waitFor(() => {
      expect(screen.queryByTestId('attachment-queue')).not.toBeInTheDocument();
    });
  });
});
