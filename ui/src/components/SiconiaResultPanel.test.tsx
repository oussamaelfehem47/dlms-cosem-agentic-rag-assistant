import React from 'react';
import { render, screen } from '@testing-library/react';
import type { JavaSiconiaResult } from '../types';
import { SiconiaResultPanel } from './SiconiaResultPanel';

describe('SiconiaResultPanel', () => {
  it('renders the same rich XML panel structure for direct and wrapped XML results', () => {
    const directResult: JavaSiconiaResult = {
      inputClass: 'XML_TRACE',
      processingMetadata: {
        normalizedInputClass: 'XML_TRACE',
        provenance: 'STRUCTURED_DIRECT',
        warnings: [],
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
    };

    const wrappedResult: JavaSiconiaResult = {
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
    };

    const { rerender } = render(<SiconiaResultPanel result={directResult} />);

    expect(screen.getByText('SICONIA Analysis')).toBeInTheDocument();
    expect(screen.getByText('XML Trace')).toBeInTheDocument();
    expect(screen.getByText('Structured')).toBeInTheDocument();
    expect(screen.getByText('0x1342')).toBeInTheDocument();
    expect(screen.getByText('DCU-01')).toBeInTheDocument();

    rerender(<SiconiaResultPanel result={wrappedResult} />);

    expect(screen.getByText('SICONIA Analysis')).toBeInTheDocument();
    expect(screen.getByText('XML Trace')).toBeInTheDocument();
    expect(screen.getByText('Heuristic')).toBeInTheDocument();
    expect(screen.getByText('0x1342')).toBeInTheDocument();
    expect(screen.getByText('DCU-01')).toBeInTheDocument();
  });

  it('renders a heuristic provenance label and event table for normalized XML traces', () => {
    render(
      <SiconiaResultPanel
        result={{
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
    expect(screen.getByText('Recovered embedded XML from wrapped prose input')).toBeInTheDocument();
    expect(screen.getByText('0x1342')).toBeInTheDocument();
    expect(screen.getByText('DCU-01')).toBeInTheDocument();
  });

  it('renders a raw fallback state with warnings instead of the old generic message', () => {
    render(
      <SiconiaResultPanel
        result={{
          inputClass: 'XML_TRACE',
          processingMetadata: {
            normalizedInputClass: 'XML_TRACE',
            provenance: 'RAW_FALLBACK',
            warnings: ['Structured event extraction did not match a supported schema'],
            extractorNote: 'valid XML interpreted from raw input',
          },
          xmlTrace: {
            events: [],
            parseErrors: ['No supported event fields found'],
            rawXml: '<Envelope><Payload/></Envelope>',
          },
        }}
      />
    );

    expect(screen.getByText('Raw fallback')).toBeInTheDocument();
    expect(screen.getByText('Structured event extraction did not match a supported schema')).toBeInTheDocument();
    expect(screen.getByText(/XML was detected, but the structure did not match the supported event schema/i)).toBeInTheDocument();
  });
});
