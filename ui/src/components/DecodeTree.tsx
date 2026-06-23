import React from 'react';
import {
  IonAccordion, IonAccordionGroup, IonItem, IonLabel,
  IonBadge, IonText, IonIcon, IonCard, IonCardContent,
} from '@ionic/react';
import {
  checkmarkCircle, closeCircle, warningOutline,
} from 'ionicons/icons';
import { DecodeResult, AxdrResult } from '../types';

// ── Field row ─────────────────────────────────────────────────────────────────
const Field: React.FC<{ label: string; value: unknown; mono?: boolean }> = ({
  label, value, mono = false
}) => (
  <div style={{ display: 'flex', padding: '4px 0', borderBottom: '1px solid var(--chat-border)' }}>
    <IonText color="medium" style={{ width: 160, fontSize: 12, flexShrink: 0 }}>
      {label}
    </IonText>
    <IonText style={{ fontSize: 12, fontFamily: mono ? 'monospace' : 'inherit', wordBreak: 'break-all', color: 'var(--chat-text)' }}>
      {String(value ?? '—')}
    </IonText>
  </div>
);

// ── CRC validity badge ────────────────────────────────────────────────────────
const CrcBadge: React.FC<{ valid: boolean; label: string }> = ({ valid, label }) => (
  <IonBadge color={valid ? 'success' : 'danger'} style={{ marginRight: 4, fontSize: 10 }}>
    <IonIcon icon={valid ? checkmarkCircle : closeCircle} style={{ marginRight: 2 }} />
    {label}
  </IonBadge>
);

// ── AXDR recursive renderer ───────────────────────────────────────────────────
const AxdrNode: React.FC<{ node: AxdrResult; depth?: number }> = ({ node, depth = 0 }) => {
  const isCompound = node.type === 'structure' || node.type === 'array';
  const indent = depth * 16;

  if (isCompound) {
    return (
      <div style={{ marginLeft: indent }}>
        <IonText color="secondary" style={{ fontSize: 12, fontWeight: 600 }}>
          [{node.type.toUpperCase()}] {node.children?.length ?? 0} elements
        </IonText>
        {node.children?.map((child, i) => (
          <AxdrNode key={i} node={child} depth={depth + 1} />
        ))}
      </div>
    );
  }

  return (
    <div style={{ marginLeft: indent, padding: '2px 0' }}>
      <IonText color="medium" style={{ fontSize: 11 }}>{node.type} </IonText>
      <IonText style={{ fontSize: 12, fontFamily: 'monospace', color: 'var(--chat-text)' }}>
        {String(node.value ?? '(null)')}
      </IonText>
    </div>
  );
};

// ── Main component ────────────────────────────────────────────────────────────
interface Props {
  result: DecodeResult | null;
  usedFallback: boolean;
}

export const DecodeTree: React.FC<Props> = ({ result, usedFallback }) => {
  if (!result) return null;

  const stages = ['hdlc', 'apdu', 'axdr', 'obis'];
  const reachedIndex = stages.indexOf(result.stage.split('_')[0]);

  const stageColor = (stage: string) => {
    const idx = stages.indexOf(stage);
    if (result.stage === 'hdlc_error' && stage === 'hdlc') return 'danger';
    if (result.stage === 'hdlc_supervisory' && stage === 'hdlc') return 'warning';
    if (idx <= reachedIndex) return 'success';
    return 'medium';
  };

  return (
    <IonCard style={{ margin: 0 }}>
      <IonCardContent style={{ padding: 8 }}>
        {/* Supervisory / management frame banner */}
        {result.stage === 'hdlc_supervisory' && result.hdlc && (
          <div style={{
            padding: '10px 14px', background: 'rgba(217,119,6,0.08)',
            border: '1px solid rgba(217,119,6,0.2)', borderRadius: 8, marginBottom: 10,
          }}>
            <IonText style={{ fontSize: 13, color: 'var(--ion-color-warning)' }}>
              <strong>Supervisory / Management Frame</strong>
              {' — '}Control type: <code>{result.hdlc.control?.type}</code>
              {result.hdlc.control?.decoded?.unnumbered_type && (
                <> ({result.hdlc.control.decoded.unnumbered_type})</>
              )}
              {result.hdlc.control?.decoded?.supervisory_type && (
                <> ({result.hdlc.control.decoded.supervisory_type})</>
              )}
              . No APDU payload.
            </IonText>
          </div>
        )}

        {/* Stage progress bar */}
        <div style={{ display: 'flex', gap: 4, marginBottom: 12 }}>
          {stages.map(s => (
            <IonBadge
              key={s}
              color={stageColor(s)}
              style={{ flex: 1, textAlign: 'center', fontSize: 10, padding: '4px 2px' }}
            >
              {s.toUpperCase()}
            </IonBadge>
          ))}
        </div>

        {usedFallback && (
          <div style={{ marginBottom: 8, padding: 8, background: 'rgba(217,119,6,0.08)', borderRadius: 6 }}>
            <IonIcon icon={warningOutline} color="warning" style={{ marginRight: 4 }} />
            <IonText color="warning" style={{ fontSize: 12 }}>
              Deterministic fallback summary is being shown for this result
            </IonText>
          </div>
        )}

        <IonAccordionGroup multiple>

          {/* HDLC */}
          {result.hdlc && (
            <IonAccordion value="hdlc">
              <IonItem slot="header" color="light">
                <IonLabel>
                  HDLC Layer
                  <CrcBadge valid={result.hdlc.hcs_valid} label="HCS" />
                  <CrcBadge valid={result.hdlc.fcs_valid} label="FCS" />
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <Field label="Frame Type" value={result.hdlc.frame_type} />
                <Field label="Frame Length" value={result.hdlc.frame_length} />
                <Field label="Dest Address"
                       value={`${result.hdlc.dest_address?.raw} (${result.hdlc.dest_address?.value})`}
                       mono />
                <Field label="Src Address"
                       value={`${result.hdlc.src_address?.raw} (${result.hdlc.src_address?.value})`}
                       mono />
                <Field label="Control Type" value={result.hdlc.control?.type} />
                <Field label="Info Length" value={result.hdlc.information_length} />
                <Field label="Info Hex" value={result.hdlc.information_hex ? (result.hdlc.information_hex.slice(0, 32) + (result.hdlc.information_hex.length > 32 ? '...' : '')) : ''} mono />
                {result.hdlc.errors.length > 0 && (
                  <IonText color="danger" style={{ fontSize: 11 }}>
                    ⚠ {result.hdlc.errors.join(', ')}
                  </IonText>
                )}
              </div>
            </IonAccordion>
          )}

          {/* APDU */}
          {result.apdu && (
            <IonAccordion value="apdu">
              <IonItem slot="header" color="light">
                <IonLabel>
                  APDU
                  <IonBadge color="primary" style={{ marginLeft: 8, fontSize: 10 }}>
                    {result.apdu.apdu_type}
                  </IonBadge>
                  {result.apdu.sub_type && (
                    <IonBadge color="secondary" style={{ marginLeft: 4, fontSize: 10 }}>
                      {result.apdu.sub_type}
                    </IonBadge>
                  )}
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <Field label="Type" value={result.apdu.apdu_type} />
                <Field label="Tag" value={result.apdu.apdu_tag} mono />
                <Field label="Description" value={result.apdu.description} />
                <Field label="Sub-type" value={result.apdu.sub_type ?? '—'} />
                <Field label="Payload Length" value={result.apdu.payload_length} />
                <Field label="LLC is_dlms" value={String(result.apdu.llc?.is_dlms ?? '—')} />
              </div>
            </IonAccordion>
          )}

          {/* AXDR */}
          {result.axdr && (
            <IonAccordion value="axdr">
              <IonItem slot="header" color="light">
                <IonLabel>
                  AXDR Data
                  <IonBadge color="tertiary" style={{ marginLeft: 8, fontSize: 10 }}>
                    {result.axdr.type}
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <AxdrNode node={result.axdr} />
              </div>
            </IonAccordion>
          )}

          {/* OBIS */}
          {result.obis && (
            <IonAccordion value="obis">
              <IonItem slot="header" color="light">
                <IonLabel>
                  OBIS Resolution
                  <IonBadge
                    color={result.obis.resolved ? 'success' : 'warning'}
                    style={{ marginLeft: 8, fontSize: 10 }}
                  >
                    {result.obis.obis}
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <Field label="OBIS Code" value={result.obis.obis} mono />
                <Field label="Resolved" value={String(result.obis.resolved)} />
                <Field label="Description" value={result.obis.description ?? '—'} />
                <Field label="Interface Class"
                       value={result.obis.interface_class
                         ? `IC ${result.obis.interface_class.id} — ${result.obis.interface_class.name}`
                         : '—'} />
                {result.obis.structural_decode && (
                  <>
                    <Field label="Medium" value={result.obis.structural_decode.medium} />
                    <Field label="Quantity" value={result.obis.structural_decode.quantity} />
                    <Field label="Measurement" value={result.obis.structural_decode.measurement_type} />
                  </>
                )}
              </div>
            </IonAccordion>
          )}

        </IonAccordionGroup>
      </IonCardContent>
    </IonCard>
  );
};
