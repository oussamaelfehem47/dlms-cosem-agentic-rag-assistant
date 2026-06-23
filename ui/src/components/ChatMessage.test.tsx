import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import { AssistantMessage } from './ChatMessage';

describe('AssistantMessage citations', () => {
  it('renders markdown formatting for final assistant messages', () => {
    const { container } = render(
      <AssistantMessage
        text={'## HLS Overview\nUse **HLS** with `0x83` when needed.\n\n- Step one\n- Step two'}
      />
    );

    expect(screen.getByRole('heading', { level: 2, name: 'HLS Overview' })).toBeInTheDocument();
    expect(container.querySelector('strong')?.textContent).toBe('HLS');
    expect(container.querySelector('code')?.textContent).toBe('0x83');
    expect(screen.getByText('Step one')).toBeInTheDocument();
    expect(screen.queryByText(/\*\*HLS\*\*/)).not.toBeInTheDocument();
  });

  it('renders a trailing sources footer through the structured source list', () => {
    render(
      <AssistantMessage
        text={
          'Use the local runbook for this task.\n\nSources: Confluence — Local operations (SPL); Confluence — 1. Ansible Inventory (SICCICD)'
        }
      />
    );

    expect(screen.getByText('Use the local runbook for this task.')).toBeInTheDocument();
    expect(screen.getByText('Sources')).toBeInTheDocument();
    expect(screen.queryByText(/^Sources:/)).not.toBeInTheDocument();
    expect(screen.getByText('Confluence — Local operations (SPL)')).toBeInTheDocument();
    expect(screen.getByText('Confluence — 1. Ansible Inventory (SICCICD)')).toBeInTheDocument();
  });

  it('repairs glued trailing source footers through the structured source list', () => {
    render(
      <AssistantMessage
        text={
          'Use the local runbook for this task.Sources: Confluence — Local operations (SPL); Confluence — 1. Ansible Inventory (SICCICD)'
        }
      />
    );

    expect(screen.getByText('Use the local runbook for this task.')).toBeInTheDocument();
    expect(screen.getByText('Sources')).toBeInTheDocument();
    expect(screen.queryByText(/^Sources:/)).not.toBeInTheDocument();
    expect(screen.getByText('Confluence — Local operations (SPL)')).toBeInTheDocument();
    expect(screen.getByText('Confluence — 1. Ansible Inventory (SICCICD)')).toBeInTheDocument();
  });

  it('keeps rendering inline confluence citations through the structured source list', () => {
    render(
      <AssistantMessage
        text={
          'Follow the operations page [Source: Confluence — Local operations (SPL)] before retrying.'
        }
      />
    );

    expect(screen.getByText(/Follow the operations page/)).toBeInTheDocument();
    expect(screen.queryByText(/\[Source:/)).not.toBeInTheDocument();
    expect(screen.getByText('Sources')).toBeInTheDocument();
    expect(screen.getByText('Confluence — Local operations (SPL)')).toBeInTheDocument();
  });

  it('shows SICONIA provenance badges on assistant messages', () => {
    render(
      <AssistantMessage
        text="Detected a normalized XML trace."
        siconiaAnalysis={{
          inputClass: 'XML_TRACE',
          processingMetadata: {
            normalizedInputClass: 'XML_TRACE',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
            extractorNote: 'Recovered embedded XML from wrapped prose input',
          },
          xmlTrace: {
            events: [
              {
                type: 'ALARM',
                code: '0x1342',
                timestamp: '2024-01-15T10:30:00Z',
                deviceId: 'DCU-01',
                errorCode: 'critical',
              },
            ],
            parseErrors: [],
            rawXml: '<Event />',
          },
        }}
      />
    );

    expect(screen.getByText('Heuristic')).toBeInTheDocument();
  });

  it('shows DLMS provenance badges and direct OBIS decode labels on assistant messages', () => {
    render(
      <AssistantMessage
        text="OBIS 1.0.1.8.0.255 resolves to active energy import total."
        decodeResult={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'OBIS_QUERY',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
            extractorNote: 'Recovered OBIS code from wrapped prose input',
          },
          obisResolutions: [
            {
              obis: '1.0.1.8.0.255',
              description: 'Active energy import total',
              ic: 3,
              unit: 'kWh',
              scaler: -3,
              tierUsed: 'KG',
            },
          ],
        }}
      />
    );

    expect(screen.getByTestId('assistant-dlms-provenance')).toHaveTextContent('Heuristic');
    expect(screen.getByRole('button', { name: /decode/i })).toHaveTextContent('OBIS');
  });

  it('renders decode stage labels without mojibake separators', () => {
    render(
      <AssistantMessage
        text="OBIS 1.0.1.8.0.255 resolves to active energy import total."
        decodeResult={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'OBIS_QUERY',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
            extractorNote: 'Recovered OBIS code from wrapped prose input',
          },
          obisResolutions: [
            {
              obis: '1.0.1.8.0.255',
              description: 'Active energy import total',
              ic: 3,
              unit: 'kWh',
              scaler: -3,
              tierUsed: 'KG',
            },
          ],
        }}
      />
    );

    const stageButton = screen.getByRole('button', { name: /decode/i });
    expect(stageButton.textContent).toContain('Decode');
    expect(stageButton.textContent).toContain('OBIS');
    expect(stageButton.textContent).not.toContain('Ãƒâ€š');
    expect(stageButton.textContent).not.toContain('ÃƒÆ’');
  });

  it('does not render a duplicate interpretation card for deterministic direct OBIS responses', () => {
    render(
      <AssistantMessage
        text={
          'What happened: The request was resolved as a deterministic OBIS lookup.\n' +
          'Can I trust it: Yes. The meaning comes from deterministic OBIS resolution, not from a guessed frame decode.\n' +
          'Next step: Use the structured panel for the resolved OBIS meaning, interface class, unit, and scaler.\n'
        }
        decodeResult={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'OBIS_QUERY',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
            extractorNote: 'Recovered OBIS code from wrapped prose input',
          },
          obisResolutions: [
            {
              obis: '1.0.1.8.0.255',
              description: 'Active energy import total',
              ic: 3,
              unit: 'kWh',
              scaler: -3,
              tierUsed: 'KG',
            },
          ],
        }}
      />
    );

    expect(screen.queryByText('Interpretation')).not.toBeInTheDocument();
    expect(screen.getByText(/What happened:/)).toBeInTheDocument();
    expect(screen.getByText(/deterministic OBIS lookup/i)).toBeInTheDocument();
  });

  it('does not render a duplicate interpretation card for deterministic SICONIA summaries', () => {
    render(
      <AssistantMessage
        text={
          'What it means: Alarm 0x1342 is HIGH on HES.\n' +
          'Impact: SICONIA DCU comm failure.\n' +
          'Next step: Check DCU-HES link, verify credentials.\n'
        }
        siconiaAnalysis={{
          inputClass: 'ALARM_CODE',
          processingMetadata: {
            normalizedInputClass: 'ALARM_CODE',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
          },
          alarmResults: [
            {
              code: '0x1342',
              severity: 'HIGH',
              rootCause: 'SICONIA DCU comm failure',
              remediation: 'Check DCU-HES link, verify credentials',
              affectedComponent: 'HES',
            },
          ],
        }}
      />
    );

    expect(screen.queryByText('Interpretation')).not.toBeInTheDocument();
    expect(screen.getAllByText(/What it means:/)).toHaveLength(1);
  });

  it('does not render a duplicate interpretation card for deterministic HDLC control-frame summaries', () => {
    render(
      <AssistantMessage
        text={
          'What happened: The deterministic parser decoded a valid HDLC control frame.\n' +
          'Can I trust it: Trust the control-frame classification and raw frame metadata.\n' +
          'Next step: Use the structured decode to inspect the frame type and addresses.\n'
        }
        decodeResult={{
          hdlcFrame: {
            frameType: 'U_FRAME',
            uFrameType: 'SNRM',
            clientSap: 1,
            serverSap: 1,
            fcsValid: true,
          },
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'FRAME_HEX',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
          },
        }}
      />
    );

    expect(screen.queryByText('Interpretation')).not.toBeInTheDocument();
    expect(screen.getByText(/What happened:/)).toBeInTheDocument();
  });

  it('marks invalid-FCS DLMS results as tentative instead of fully structured', () => {
    render(
      <AssistantMessage
        text="What happened: The FCS check failed for this S_FRAME."
        decodeResult={{
          hdlcFrame: {
            frameType: 'S_FRAME',
            sFrameType: 'RR',
            clientSap: 1,
            serverSap: 35651712,
            fcsValid: false,
          },
          apduType: 'UNKNOWN',
          gbtPartial: false,
          parseErrors: ['FCS invalid'],
          processingMetadata: {
            normalizedKind: 'FRAME_HEX',
            provenance: 'STRUCTURED_DIRECT',
            warnings: [],
          },
        }}
      />
    );

    expect(screen.getByTestId('assistant-dlms-provenance')).toHaveTextContent('Tentative');
  });

  it('keeps backend malformed-frame narration instead of replacing it with the generic checksum card', () => {
    render(
      <AssistantMessage
        text="What happened: The outer HDLC header parses as an RR supervisory control frame, but unexpected information field on supervisory frame.\nCan I trust it: Only the outer HDLC classification should be treated as tentative.\nNext step: Re-capture the frame."
        decodeResult={{
          hdlcFrame: {
            frameType: 'S_FRAME',
            sFrameType: 'RR',
            clientSap: 1,
            serverSap: 35651712,
            fcsValid: false,
          },
          apduType: 'UNKNOWN',
          gbtPartial: false,
          parseErrors: ['Unexpected information field on supervisory frame', 'FCS invalid'],
          processingMetadata: {
            normalizedKind: 'FRAME_HEX',
            provenance: 'STRUCTURED_DIRECT',
            warnings: [],
          },
        }}
      />
    );

    expect(screen.queryByText('Checksum failed')).not.toBeInTheDocument();
    expect(screen.getByText(/outer HDLC header parses as an RR supervisory control frame/i)).toBeInTheDocument();
  });

  it('keeps the security badge but does not render the old security helper banner', () => {
    render(
      <AssistantMessage
        text="HLS is a challenge-response authentication mechanism."
        uiCategory="security"
      />
    );

    expect(screen.getByTestId('assistant-category-badge')).toHaveTextContent('security');
    expect(screen.queryByText('Security explanation or troubleshooting context')).not.toBeInTheDocument();
  });

  it('renders a collapsed safe trace panel when orchestration metadata exists', () => {
    render(
      <AssistantMessage
        text="This answer used session context before retrieval."
        orchestrationMode="NATURAL_LANGUAGE_AGENTIC"
        plannerUsed
        plannerFallbackReason="No dominant structured candidate was found."
        explanationMode="GROUNDED_LLM"
        toolProvenance="MIXED"
        strategyMetadata={{
          selectedStrategy: 'SESSION_RECALL',
          selectedLabel: 'Session recall',
          confidence: 0.91,
          ambiguous: false,
          tentative: false,
          candidates: [],
          warnings: [],
        }}
        toolTrace={[
          {
            toolName: 'get_session_memory',
            summary: 'Loaded the last decoded frame and OBIS facts from the session state.',
            authoritative: true,
            provenance: 'STM',
          },
          {
            toolName: 'search_docs',
            summary: 'Retrieved supplementary DLMS protocol context.',
            authoritative: false,
            provenance: 'RAG',
          },
        ]}
      />
    );

    const toggle = screen.getByTestId('assistant-trace-toggle');
    expect(toggle).toHaveTextContent('How I answered');
    expect(screen.queryByTestId('assistant-trace-panel')).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(screen.getByTestId('assistant-trace-panel')).toBeInTheDocument();
    expect(screen.getByText(/Natural-language agentic/i)).toBeInTheDocument();
    expect(screen.getByText(/Session recall \(91%\)/i)).toBeInTheDocument();
    expect(screen.getByText(/get_session_memory/i)).toBeInTheDocument();
    expect(screen.getByText(/search_docs/i)).toBeInTheDocument();
    expect(screen.getByText(/No dominant structured candidate was found\./i)).toBeInTheDocument();
  });

  it('renders clean trace qualifiers for tentative and ambiguous strategies', () => {
    render(
      <AssistantMessage
        text="This answer surfaced ranked candidates."
        orchestrationMode="AMBIGUOUS_SAFE_FALLBACK"
        plannerUsed={false}
        strategyMetadata={{
          selectedStrategy: 'DLMS_FRAME_DECODE',
          selectedLabel: 'HDLC frame candidate 1',
          confidence: 0.78,
          ambiguous: true,
          tentative: true,
          candidates: [],
          warnings: [],
        }}
      />
    );

    fireEvent.click(screen.getByTestId('assistant-trace-toggle'));

    const strategyLine = screen.getByText(/HDLC frame candidate 1 \(78%\)/i);
    expect(strategyLine.textContent).toContain('· tentative');
    expect(strategyLine.textContent).toContain('· ambiguous');
    expect(strategyLine.textContent).not.toContain('Â·');
  });

  it('renders multi-artifact assistant sections from batch results', () => {
    render(
      <AssistantMessage
        text={'I analyzed 3 artifacts:\n\n- Artifact 1: U_FRAME (SNRM)\n- Artifact 2: AXDR boolean `true`\n- Artifact 3: GET_RESPONSE for OBIS `1.0.1.8.0.255`'}
        artifactResults={[
          {
            artifactId: 'artifact-1',
            index: 0,
            source: 'ATTACHMENT',
            filename: 'frame.hex',
            rawInput: '7EA00A030383CD6F7E',
            inputClass: 'HEX_FRAME',
            intent: 'FRAME_DECODE',
            explanation: 'Frame explanation.',
            explanationMode: 'DETERMINISTIC_ONLY',
            toolProvenance: 'MCP',
            decodeResult: {
              hdlcFrame: {
                frameType: 'U_FRAME',
                uFrameType: 'SNRM',
                clientSap: 1,
                serverSap: 1,
                fcsValid: true,
              },
              apduType: 'UNKNOWN',
              gbtPartial: false,
              processingMetadata: {
                normalizedKind: 'FRAME_HEX',
                provenance: 'STRUCTURED_DIRECT',
                warnings: [],
              },
            },
          },
          {
            artifactId: 'artifact-2',
            index: 1,
            source: 'ATTACHMENT',
            filename: 'scalar.hex',
            rawInput: '03 01',
            inputClass: 'QUERY',
            intent: 'APDU_ANALYSIS',
            explanation: 'What it means: The payload decodes as AXDR boolean true.\nWhy it matters: The top-level AXDR value is boolean true.\nNext step: Use the structured panel for the AXDR details.',
            explanationMode: 'DETERMINISTIC_ONLY',
            toolProvenance: 'JAVA',
            decodeResult: {
              apduType: 'UNKNOWN',
              axdrTree: {
                tag: '0x03',
                type: 'boolean',
                value: true,
              },
              gbtPartial: false,
              processingMetadata: {
                normalizedKind: 'AXDR_HEX',
                provenance: 'STRUCTURED_DIRECT',
                warnings: [],
              },
            },
          },
          {
            artifactId: 'artifact-3',
            index: 2,
            source: 'ATTACHMENT',
            filename: 'payload.hex',
            rawInput: 'C4020109060100010800FF',
            inputClass: 'QUERY',
            intent: 'APDU_ANALYSIS',
            explanation: 'APDU explanation.',
            explanationMode: 'GROUNDED_LLM',
            toolProvenance: 'JAVA',
            decodeResult: {
              apduType: 'GET_RESPONSE',
              gbtPartial: false,
              obisResolutions: [
                {
                  obis: '1.0.1.8.0.255',
                  description: 'Active energy import total',
                  ic: 3,
                  unit: 'Wh',
                  scaler: -3,
                  tierUsed: 'KG',
                },
              ],
              processingMetadata: {
                normalizedKind: 'APDU_HEX',
                provenance: 'STRUCTURED_DIRECT',
                warnings: [],
              },
            },
          },
        ]}
      />
    );

    expect(screen.getByText(/I analyzed 3 artifacts:/)).toBeInTheDocument();
    expect(screen.getAllByTestId('assistant-artifact-section')).toHaveLength(3);
    expect(screen.getByText('frame.hex')).toBeInTheDocument();
    expect(screen.getByText('scalar.hex')).toBeInTheDocument();
    expect(screen.getByText('payload.hex')).toBeInTheDocument();
    expect(screen.getByText('Frame explanation.')).toBeInTheDocument();
    expect(screen.getByText(/The payload decodes as AXDR boolean true\./)).toBeInTheDocument();
    expect(screen.getByText('APDU explanation.')).toBeInTheDocument();
    expect(screen.getByText('Decode - U_FRAME (SNRM)')).toBeInTheDocument();
    expect(screen.getByText('Decode - AXDR')).toBeInTheDocument();
    expect(screen.getByText('Decode - GET_RESPONSE')).toBeInTheDocument();
    expect(screen.getByText('AXDR')).toBeInTheDocument();
    expect(screen.getAllByText('APDU').length).toBeGreaterThan(0);
  });

  it('normalizes mojibake before copying assistant response text', async () => {
    const copySpy = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: copySpy },
    });

    render(<AssistantMessage text={'Status âœ… â€” DLMS Â§GreenBook'} />);

    fireEvent.click(screen.getByRole('button', { name: /copy response/i }));

    await waitFor(() => {
      expect(copySpy).toHaveBeenCalledWith('Status ✅ — DLMS §GreenBook');
    });
  });

  it('normalizes mojibake before copying markdown code blocks', async () => {
    const copySpy = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: copySpy },
    });

    render(<AssistantMessage text={'```text\nDLMS â€” Â§GreenBook\n```'} />);

    fireEvent.click(screen.getByRole('button', { name: /copy code block/i }));

    await waitFor(() => {
      expect(copySpy).toHaveBeenCalledWith('DLMS — §GreenBook');
    });
  });
});

