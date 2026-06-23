import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import { DecodeTreePanel } from './DecodeTreePanel';

describe('DecodeTreePanel', () => {
  it('does not render placeholder null leaves in the AXDR tree', () => {
    render(
      <DecodeTreePanel
        result={{
          apduType: 'GET_RESPONSE',
          gbtPartial: false,
          axdrTree: {
            type: 'structure',
            tag: '0x02',
            children: [
              { type: 'octet-string', tag: '0x09' },
              { type: 'visible-string', tag: '0x0A', value: '1.0.1.8.0.255' },
            ],
          },
        }}
      />,
    );

    expect(screen.getByText('AXDR Data')).toBeInTheDocument();
    expect(screen.queryByText('(null)')).not.toBeInTheDocument();
    expect(screen.getByText('1.0.1.8.0.255')).toBeInTheDocument();
  });

  it('hides the APDU accordion for direct OBIS lookups', () => {
    render(
      <DecodeTreePanel
        result={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'OBIS_QUERY',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
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
      />,
    );

    expect(screen.queryByText(/^APDU$/)).not.toBeInTheDocument();
    expect(screen.getByText('OBIS Resolutions')).toBeInTheDocument();
  });

  it('hides the APDU accordion for raw AXDR payloads', () => {
    render(
      <DecodeTreePanel
        result={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          processingMetadata: {
            normalizedKind: 'AXDR_HEX',
            provenance: 'STRUCTURED_HEURISTIC',
            warnings: [],
          },
          axdrTree: {
            type: 'structure',
            tag: '0x02',
            children: [
              { type: 'boolean', tag: '0x03', value: true },
            ],
          },
        }}
      />,
    );

    expect(screen.queryByText(/^APDU$/)).not.toBeInTheDocument();
    expect(screen.getByText('AXDR Data')).toBeInTheDocument();
  });

  it('hides the APDU accordion for HDLC control frames with no payload by design', () => {
    render(
      <DecodeTreePanel
        result={{
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
        }}
      />,
    );

    expect(screen.queryByText(/^APDU$/)).not.toBeInTheDocument();
    expect(screen.getByText('HDLC Frame')).toBeInTheDocument();
  });

  it('uses the control-frame subtype as the top decode summary badge', () => {
    render(
      <DecodeTreePanel
        result={{
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
        }}
      />,
    );

    expect(screen.getByText('Decode Result')).toBeInTheDocument();
    expect(screen.getAllByText('U_FRAME (SNRM)')).not.toHaveLength(0);
    expect(screen.queryByText('UNKNOWN')).not.toBeInTheDocument();
  });

  it('uses singular byte wording for single-byte payloads', () => {
    render(
      <DecodeTreePanel
        result={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          frameLength: 1,
          rawHex: '00',
          processingMetadata: {
            normalizedKind: 'AXDR_HEX',
            provenance: 'STRUCTURED_DIRECT',
            warnings: [],
          },
          axdrTree: {
            type: 'null',
            tag: '0x00',
          },
        }}
      />,
    );

    expect(screen.getAllByText('1 byte')).toHaveLength(2);
    expect(screen.queryByText('1 bytes')).not.toBeInTheDocument();
  });

  it('normalizes mojibake before copying decode panel text', async () => {
    const copySpy = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: copySpy },
    });

    render(
      <DecodeTreePanel
        result={{
          apduType: 'UNKNOWN',
          gbtPartial: false,
          rawHex: 'âœ… Â§',
          frameLength: 2,
          processingMetadata: {
            normalizedKind: 'AXDR_HEX',
            provenance: 'STRUCTURED_DIRECT',
            warnings: [],
          },
          axdrTree: {
            type: 'null',
            tag: '0x00',
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /^copy$/i }));

    await waitFor(() => {
      expect(copySpy).toHaveBeenCalledWith('✅ §');
    });
  });
});
