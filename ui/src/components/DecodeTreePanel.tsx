import React, { useCallback, useState } from 'react';
import {
  IonAccordion,
  IonAccordionGroup,
  IonBadge,
  IonCard,
  IonCardContent,
  IonIcon,
  IonItem,
  IonLabel,
  IonText,
} from '@ionic/react';
import {
  checkmarkCircle,
  checkmarkOutline,
  closeCircle,
  copyOutline,
  warningOutline,
} from 'ionicons/icons';
import { JavaAxdrValue, JavaDecodeResult, JavaObisResolution } from '../types';
import { normalizeVisibleBlock, normalizeVisibleText } from '../chat/textNormalization';

const formatByteCount = (count: number): string => `${count} ${count === 1 ? 'byte' : 'bytes'}`;
const EMPTY_VALUE = '\u2014';

const Field: React.FC<{ label: string; value: React.ReactNode; mono?: boolean }> = ({
  label,
  value,
  mono = false,
}) => (
  <div style={{ display: 'flex', padding: '4px 0', borderBottom: '1px solid var(--chat-border)' }}>
    <IonText color="medium" style={{ width: 160, fontSize: 12, flexShrink: 0 }}>
      {label}
    </IonText>
    <div
      style={{
        fontSize: 12,
        fontFamily: mono ? 'monospace' : 'inherit',
        wordBreak: 'break-all',
        color: 'var(--chat-text)',
        flex: 1,
      }}
    >
      {value === null || value === undefined
        ? EMPTY_VALUE
        : typeof value === 'string'
          ? normalizeVisibleText(value)
          : value}
    </div>
  </div>
);

const FcsBadge: React.FC<{ valid: boolean }> = ({ valid }) => (
  <IonBadge color={valid ? 'success' : 'danger'} style={{ marginLeft: 8, fontSize: 10 }}>
    <IonIcon icon={valid ? checkmarkCircle : closeCircle} style={{ marginRight: 2 }} />
    FCS {valid ? 'OK' : 'INVALID'}
  </IonBadge>
);

const GbtBadge: React.FC<{ partial: boolean }> = ({ partial }) =>
  partial ? (
    <IonBadge color="warning" style={{ marginLeft: 8, fontSize: 10 }}>
      GBT PARTIAL
    </IonBadge>
  ) : null;

const CopyButton: React.FC<{ text: string }> = ({ text }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(normalizeVisibleBlock(text));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignore clipboard failures.
    }
  }, [text]);

  return (
    <button
      type="button"
      onClick={handleCopy}
      title="Copy to clipboard"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '2px 8px',
        borderRadius: 4,
        border: '1px solid var(--chat-border)',
        background: 'transparent',
        cursor: 'pointer',
        color: copied ? 'var(--ion-color-success)' : 'var(--chat-muted-2)',
        fontSize: 11,
        fontFamily: 'inherit',
        transition: 'all 0.15s',
      }}
    >
      <IonIcon icon={copied ? checkmarkOutline : copyOutline} style={{ fontSize: 11 }} />
      {copied ? 'Copied' : 'Copy'}
    </button>
  );
};

const AxdrNode: React.FC<{ node: JavaAxdrValue; depth?: number }> = ({ node, depth = 0 }) => {
  const isCompound = node.type === 'structure' || node.type === 'array';
  const indent = depth * 16;
  const hasScalarValue = node.value !== null && node.value !== undefined && String(node.value) !== '';

  if (isCompound) {
    return (
      <div style={{ marginLeft: indent }}>
        <IonText color="secondary" style={{ fontSize: 12, fontWeight: 600 }}>
          [{node.type.toUpperCase()}] {node.children?.length ?? 0} elements
        </IonText>
        {node.children?.map((child, index) => (
          <AxdrNode key={index} node={child} depth={depth + 1} />
        ))}
      </div>
    );
  }

  if (!hasScalarValue) {
    return null;
  }

  return (
    <div style={{ marginLeft: indent, padding: '2px 0' }}>
      <IonText color="medium" style={{ fontSize: 11 }}>
        {node.type}{' '}
      </IonText>
      <IonText style={{ fontSize: 12, fontFamily: 'monospace', color: 'var(--chat-text)' }}>
        {normalizeVisibleText(String(node.value))}
      </IonText>
    </div>
  );
};

const TierBadge: React.FC<{ tier: string }> = ({ tier }) => {
  const colorMap: Record<string, string> = {
    KG: 'success',
    STRUCTURAL: 'tertiary',
    RAG: 'warning',
  };

  return (
    <IonBadge color={colorMap[tier] || 'medium'} style={{ marginLeft: 4, fontSize: 9 }}>
      {tier}
    </IonBadge>
  );
};

const displayFrameType = (frameType: unknown, sub?: string | null): string => {
  if (frameType == null) return 'UNKNOWN';
  if (typeof frameType === 'string') return sub ? `${frameType} (${sub})` : frameType;
  if (typeof frameType === 'object') {
    const candidate = frameType as Record<string, unknown>;
    const name = typeof candidate.name === 'string' ? candidate.name : JSON.stringify(frameType);
    const subtype = typeof candidate.subtype === 'string' ? candidate.subtype : sub ?? null;
    return subtype ? `${name} (${subtype})` : name;
  }
  return String(frameType);
};

const FrameTypeDisplay: React.FC<{
  frameType: unknown;
  uFrameType?: string | null;
  sFrameType?: string | null;
}> = ({ frameType, uFrameType, sFrameType }) => {
  const subtype = uFrameType ?? sFrameType ?? null;
  return <>{displayFrameType(frameType, subtype)}</>;
};

const SeverityBadge: React.FC<{ severity: string }> = ({ severity }) => {
  const colorMap: Record<string, string> = {
    CRITICAL: 'danger',
    HIGH: 'danger',
    MEDIUM: 'warning',
    LOW: 'medium',
    INFO: 'primary',
  };

  return (
    <IonBadge color={colorMap[severity] || 'medium'} style={{ marginLeft: 4, fontSize: 10 }}>
      {severity}
    </IonBadge>
  );
};

interface Props {
  result: JavaDecodeResult | null;
}

export const DecodeTreePanel: React.FC<Props> = ({ result }) => {
  if (!result) return null;

  const normalizedKind = result.processingMetadata?.normalizedKind;
  const isDirectObis = normalizedKind === 'OBIS_QUERY';
  const isDirectAxdr = normalizedKind === 'AXDR_HEX';
  const isDirectApdu = normalizedKind === 'APDU_HEX';
  const hasHdlc = Boolean(result.hdlcFrame);
  const isControlFrame = result.hdlcFrame?.frameType === 'U_FRAME' || result.hdlcFrame?.frameType === 'S_FRAME';
  const hasAxdr = Boolean(result.axdrTree);
  const hasObis = Boolean(result.obisResolutions && result.obisResolutions.length > 0);
  const hasErrors = Boolean(result.parseErrors && result.parseErrors.length > 0);
  const hasRawHex = Boolean(result.rawHex);
  const showApdu = Boolean(result.apduType)
    && !isControlFrame
    && !isDirectObis
    && !isDirectAxdr
    && !(result.apduType === 'UNKNOWN' && !hasHdlc && !isDirectApdu);
  const controlFrameLabel = hasHdlc && isControlFrame
    ? displayFrameType(
      result.hdlcFrame?.frameType,
      result.hdlcFrame?.uFrameType ?? result.hdlcFrame?.sFrameType ?? null,
    )
    : null;
  const summaryBadgeLabel = controlFrameLabel
    ? controlFrameLabel
    : isDirectObis
      ? 'OBIS Lookup'
      : isDirectAxdr
        ? 'AXDR Decode'
        : isDirectApdu
          ? `APDU Decode${result.apduType && result.apduType !== 'UNKNOWN' ? ` - ${result.apduType}` : ''}`
          : result.apduType;

  return (
    <IonCard style={{ margin: 0 }}>
      <IonCardContent style={{ padding: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
          <IonText style={{ fontSize: 13, fontWeight: 600, color: 'var(--chat-text)' }}>
            Decode Result
          </IonText>
          {summaryBadgeLabel && (
            <IonBadge color="primary" style={{ fontSize: 10 }}>
              {normalizeVisibleText(summaryBadgeLabel)}
            </IonBadge>
          )}
          {result.frameLength != null && (
            <IonBadge color="medium" style={{ fontSize: 10 }}>
              {formatByteCount(result.frameLength)}
            </IonBadge>
          )}
          <GbtBadge partial={result.gbtPartial} />
          {hasErrors && (
            <IonBadge color="danger" style={{ fontSize: 10 }}>
              {result.parseErrors!.length} error{result.parseErrors!.length > 1 ? 's' : ''}
            </IonBadge>
          )}
          {result.anomalies && result.anomalies.length > 0 && (
            <IonBadge color="warning" style={{ fontSize: 10 }}>
              {result.anomalies.length} anomaly{result.anomalies.length > 1 ? 's' : ''}
            </IonBadge>
          )}
        </div>

        <IonAccordionGroup multiple>
          {hasHdlc && (
            <IonAccordion value="hdlc">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>HDLC Frame</span>
                  <FrameTypeDisplay
                    frameType={result.hdlcFrame!.frameType}
                    uFrameType={result.hdlcFrame!.uFrameType}
                    sFrameType={result.hdlcFrame!.sFrameType}
                  />
                  <FcsBadge valid={result.hdlcFrame!.fcsValid} />
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <Field
                  label="Frame Type"
                  value={(
                    <FrameTypeDisplay
                      frameType={result.hdlcFrame!.frameType}
                      uFrameType={result.hdlcFrame!.uFrameType}
                      sFrameType={result.hdlcFrame!.sFrameType}
                    />
                  )}
                />
                <Field
                  label="Client SAP"
                  value={`${result.hdlcFrame!.clientSap} (0x${result.hdlcFrame!.clientSap.toString(16).padStart(2, '0')})`}
                  mono
                />
                <Field
                  label="Server SAP"
                  value={`${result.hdlcFrame!.serverSap} (0x${result.hdlcFrame!.serverSap.toString(16).padStart(2, '0')})`}
                  mono
                />
                {result.hdlcFrame!.sFrameType && (
                  <Field label="S-frame Subtype" value={result.hdlcFrame!.sFrameType} />
                )}
                {result.hdlcFrame!.uFrameType && (
                  <Field label="U-frame Subtype" value={result.hdlcFrame!.uFrameType} />
                )}
                <Field label="FCS Status" value={result.hdlcFrame!.fcsValid ? '✅ Valid' : '❌ Invalid'} />
                {result.frameLength != null && (
                  <Field label="Frame Length" value={formatByteCount(result.frameLength)} />
                )}
              </div>
            </IonAccordion>
          )}

          {showApdu && (
            <IonAccordion value="apdu">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>APDU</span>
                  <IonBadge color="primary" style={{ marginLeft: 4, fontSize: 10 }}>
                    {result.apduType}
                  </IonBadge>
                  {result.apduTag && (
                    <IonBadge color="dark" style={{ fontSize: 10 }}>
                      {result.apduTag}
                    </IonBadge>
                  )}
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <Field label="APDU Type" value={result.apduType} />
                {result.apduTag && (
                  <Field label="APDU Tag" value={result.apduTag} mono />
                )}
                {result.rawHex && result.rawHex !== 'null' && result.rawHex !== '' && (
                  <Field
                    label="Raw Hex"
                    value={result.rawHex.length > 32 ? `${result.rawHex.slice(0, 32)}...` : result.rawHex}
                    mono
                  />
                )}
              </div>
            </IonAccordion>
          )}

          {hasAxdr && (
            <IonAccordion value="axdr">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>AXDR Data</span>
                  <IonBadge color="tertiary" style={{ marginLeft: 4, fontSize: 10 }}>
                    {result.axdrTree!.type}
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <AxdrNode node={result.axdrTree!} />
              </div>
            </IonAccordion>
          )}

          {hasObis && (
            <IonAccordion value="obis">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>OBIS Resolutions</span>
                  <IonBadge color="success" style={{ marginLeft: 4, fontSize: 10 }}>
                    {result.obisResolutions!.length} resolved
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                {result.obisResolutions!.map((resolution: JavaObisResolution, index: number) => (
                  <div
                    key={index}
                    style={{
                      padding: '8px 0',
                      borderBottom: index < result.obisResolutions!.length - 1 ? '1px solid var(--chat-border)' : 'none',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
                      <IonText style={{ fontSize: 13, fontWeight: 600, fontFamily: 'monospace', color: 'var(--chat-text)' }}>
                        {resolution.obis}
                      </IonText>
                      <TierBadge tier={resolution.tierUsed} />
                    </div>
                    <IonText style={{ fontSize: 12, color: 'var(--chat-muted)' }}>
                      {normalizeVisibleText(resolution.description || 'No description')}
                      {resolution.ic != null && (
                        <> {' · '}IC {resolution.ic}{resolution.icName ? ` — ${normalizeVisibleText(resolution.icName)}` : ''}</>
                      )}
                      {resolution.unit ? ` · ${normalizeVisibleText(resolution.unit)}` : ''}
                      {resolution.scaler != null && resolution.scaler !== 0 ? ` · ×10^${resolution.scaler}` : ''}
                    </IonText>
                  </div>
                ))}
              </div>
            </IonAccordion>
          )}

          {hasErrors && (
            <IonAccordion value="errors">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>Parse Errors</span>
                  <IonBadge color="danger" style={{ marginLeft: 4, fontSize: 10 }}>
                    {result.parseErrors!.length}
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                {result.parseErrors!.map((error: string, index: number) => (
                  <div
                    key={index}
                    style={{
                      padding: '6px 10px',
                      marginBottom: 4,
                      background: 'rgba(239,68,68,0.08)',
                      border: '1px solid rgba(239,68,68,0.15)',
                      borderRadius: 6,
                      fontSize: 12,
                      color: 'var(--ion-color-danger)',
                    }}
                  >
                    <IonIcon icon={warningOutline} style={{ marginRight: 4, fontSize: 10 }} />
                    {normalizeVisibleText(error)}
                  </div>
                ))}
              </div>
            </IonAccordion>
          )}

          {result.anomalies && result.anomalies.length > 0 && (
            <IonAccordion value="anomalies">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>Protocol Anomalies</span>
                  <IonBadge color="warning" style={{ marginLeft: 4, fontSize: 10 }}>
                    {result.anomalies.length}
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                {result.anomalies.map((anomaly: string, index: number) => (
                  <div
                    key={index}
                    style={{
                      padding: '8px 12px',
                      marginBottom: 6,
                      background: 'rgba(217,119,6,0.08)',
                      border: '1px solid rgba(217,119,6,0.2)',
                      borderRadius: 8,
                      fontSize: 12,
                      color: 'var(--ion-color-warning)',
                      fontWeight: 500,
                    }}
                  >
                    <IonIcon icon={warningOutline} style={{ marginRight: 6, fontSize: 13, verticalAlign: 'middle' }} />
                    {normalizeVisibleText(anomaly)}
                  </div>
                ))}
              </div>
            </IonAccordion>
          )}

          {hasRawHex && (
            <IonAccordion value="rawHex">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>Raw Hex</span>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
                  <IonText color="medium" style={{ fontSize: 11 }}>
                    {formatByteCount(result.rawHex!.length / 2)}
                  </IonText>
                  <CopyButton text={result.rawHex!} />
                </div>
                <pre
                  style={{
                    margin: 0,
                    padding: '10px 12px',
                    background: 'rgba(0,0,0,0.04)',
                    borderRadius: 6,
                    fontSize: 12,
                    lineHeight: 1.5,
                    fontFamily: '"Fira Code", "Consolas", "Cascadia Code", monospace',
                    color: 'var(--chat-text)',
                    overflowX: 'auto',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                  }}
                >
                  {(result.rawHex && result.rawHex !== 'null')
                    ? (result.rawHex.match(/.{1,2}/g)?.join(' ') ?? result.rawHex)
                    : '(no data)'}
                </pre>
              </div>
            </IonAccordion>
          )}
        </IonAccordionGroup>
      </IonCardContent>
    </IonCard>
  );
};

export { Field, SeverityBadge, TierBadge };
