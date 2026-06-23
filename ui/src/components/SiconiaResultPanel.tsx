import React from 'react';
import {
  IonAccordion,
  IonAccordionGroup,
  IonBadge,
  IonCard,
  IonCardContent,
  IonChip,
  IonIcon,
  IonItem,
  IonLabel,
  IonText,
} from '@ionic/react';
import {
  documentTextOutline,
  flashOutline,
  informationCircleOutline,
  warningOutline,
} from 'ionicons/icons';
import {
  JavaAlarmDecodeResult,
  JavaLogAnalysis,
  JavaSiconiaProcessingMetadata,
  JavaSiconiaResult,
  JavaXmlTrace,
} from '../types';
import { normalizeVisibleText } from '../chat/textNormalization';
import { Field, SeverityBadge } from './DecodeTreePanel';

const EMPTY_VALUE = '\u2014';

const SEVERITY_COLORS: Record<string, { bg: string; border: string; text: string }> = {
  CRITICAL: { bg: 'rgba(220,38,38,0.12)', border: 'rgba(220,38,38,0.3)', text: 'var(--ion-color-danger)' },
  HIGH: { bg: 'rgba(239,68,68,0.08)', border: 'rgba(239,68,68,0.2)', text: 'var(--ion-color-danger)' },
  MEDIUM: { bg: 'rgba(217,119,6,0.08)', border: 'rgba(217,119,6,0.2)', text: 'var(--ion-color-warning)' },
  LOW: { bg: 'rgba(107,114,128,0.08)', border: 'rgba(107,114,128,0.2)', text: 'var(--chat-muted)' },
  INFO: { bg: 'rgba(59,130,246,0.08)', border: 'rgba(59,130,246,0.2)', text: 'var(--ion-color-primary)' },
};

const LAYER_COLORS: Record<string, string> = {
  WAN: 'primary',
  PLC: 'warning',
  RF: 'tertiary',
  HES: 'danger',
  DLMS: 'success',
};

const PROVENANCE_LABELS: Record<NonNullable<JavaSiconiaProcessingMetadata['provenance']>, string> = {
  STRUCTURED_DIRECT: 'Structured',
  STRUCTURED_HEURISTIC: 'Heuristic',
  RAW_FALLBACK: 'Raw fallback',
};

const PROVENANCE_BADGE_COLORS: Record<NonNullable<JavaSiconiaProcessingMetadata['provenance']>, string> = {
  STRUCTURED_DIRECT: 'success',
  STRUCTURED_HEURISTIC: 'warning',
  RAW_FALLBACK: 'medium',
};

const AlarmCard: React.FC<{ alarm: JavaAlarmDecodeResult }> = ({ alarm }) => {
  const colors = SEVERITY_COLORS[alarm.severity] || SEVERITY_COLORS.MEDIUM;

  return (
    <div
      style={{
        padding: '10px 12px',
        marginBottom: 8,
        background: colors.bg,
        border: `1px solid ${colors.border}`,
        borderRadius: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, flexWrap: 'wrap' }}>
        <IonIcon icon={flashOutline} style={{ fontSize: 12, color: colors.text }} />
        <IonText style={{ fontSize: 13, fontFamily: 'monospace', fontWeight: 600, color: 'var(--chat-text)' }}>
          {alarm.code}
        </IonText>
        <SeverityBadge severity={alarm.severity} />
        <IonBadge color={LAYER_COLORS[alarm.affectedComponent] || 'medium'} style={{ fontSize: 10 }}>
          {alarm.affectedComponent}
        </IonBadge>
      </div>

      <Field label="Root Cause" value={normalizeVisibleText(alarm.rootCause)} />
      <Field label="Remediation" value={normalizeVisibleText(alarm.remediation)} />
    </div>
  );
};

const LogAnalysisCard: React.FC<{ log: JavaLogAnalysis }> = ({ log }) => (
  <div style={{ padding: '8px 0' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 10, flexWrap: 'wrap' }}>
      <IonBadge color={LAYER_COLORS[log.dominantLayer] || 'medium'} style={{ fontSize: 11 }}>
        Layer: {log.dominantLayer}
      </IonBadge>
      <SeverityBadge severity={log.highestSeverity} />
    </div>

    <Field label="Line Count" value={log.lineCount} />
    <Field label="Error Lines" value={log.errorLineCount} />

    <div style={{ marginTop: 8 }}>
      <IonText color="medium" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
        Issue Categories
      </IonText>
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
        {log.issueCategories?.length ? log.issueCategories.map((category, index) => (
          <IonChip
            key={index}
            style={{
              margin: 0,
              fontSize: 10,
              fontWeight: 600,
              height: 22,
              '--background': category === 'SECURITY'
                ? 'rgba(220,38,38,0.1)'
                : category === 'CONNECTIVITY'
                  ? 'rgba(59,130,246,0.1)'
                  : category === 'HARDWARE'
                    ? 'rgba(217,119,6,0.1)'
                    : category === 'SOFTWARE'
                      ? 'rgba(124,58,237,0.1)'
                      : 'rgba(107,114,128,0.1)',
              '--color': category === 'SECURITY'
                ? 'var(--ion-color-danger)'
                : category === 'CONNECTIVITY'
                  ? 'var(--ion-color-primary)'
                  : category === 'HARDWARE'
                    ? 'var(--ion-color-warning)'
                    : category === 'SOFTWARE'
                      ? 'var(--ion-color-tertiary)'
                      : 'var(--chat-muted)',
            } as React.CSSProperties}
          >
            {category}
          </IonChip>
        )) : (
          <IonText color="medium" style={{ fontSize: 11 }}>
            {EMPTY_VALUE}
          </IonText>
        )}
      </div>
    </div>
  </div>
);

const XmlTraceCard: React.FC<{
  xml: JavaXmlTrace | null | undefined;
  processingMetadata?: JavaSiconiaProcessingMetadata | null;
}> = ({ xml, processingMetadata }) => {
  let events: Array<{ type: string; code: string; timestamp: string; deviceId: string; errorCode: string }> = [];
  let parseError: string | null = null;

  try {
    if (xml && typeof xml === 'object') {
      const obj = xml as Record<string, unknown>;
      if (Array.isArray(obj.events)) {
        events = obj.events.map((event: unknown) => {
          const candidate = event as Record<string, unknown>;
          return {
            type: normalizeVisibleText(String(candidate.type || candidate.eventType || EMPTY_VALUE)),
            code: normalizeVisibleText(String(candidate.code || candidate.eventCode || EMPTY_VALUE)),
            timestamp: normalizeVisibleText(String(candidate.timestamp || candidate.ts || EMPTY_VALUE)),
            deviceId: normalizeVisibleText(String(candidate.deviceId || candidate.device_id || candidate.source || EMPTY_VALUE)),
            errorCode: normalizeVisibleText(String(candidate.errorCode || candidate.error_code || candidate.severity || EMPTY_VALUE)),
          };
        });
      } else if (obj.event) {
        const candidate = (obj.event as Record<string, unknown>) || obj;
        events = [{
          type: normalizeVisibleText(String(candidate.type || candidate.eventType || obj.type || EMPTY_VALUE)),
          code: normalizeVisibleText(String(candidate.code || candidate.eventCode || obj.code || EMPTY_VALUE)),
          timestamp: normalizeVisibleText(String(candidate.timestamp || candidate.ts || obj.timestamp || EMPTY_VALUE)),
          deviceId: normalizeVisibleText(String(candidate.deviceId || candidate.device_id || candidate.source || obj.source || EMPTY_VALUE)),
          errorCode: normalizeVisibleText(String(candidate.errorCode || candidate.error_code || candidate.severity || obj.severity || EMPTY_VALUE)),
        }];
      }
      if (Array.isArray(obj.parseErrors) && obj.parseErrors.length > 0) {
        parseError = normalizeVisibleText(String(obj.parseErrors[0]));
      } else if (obj.parseError || obj.error) {
        parseError = normalizeVisibleText(String(obj.parseError || obj.error));
      }
    }
  } catch {
    // Ignore parse errors and fall back to the empty-state rendering.
  }

  const warnings = (processingMetadata?.warnings ?? []).map((warning) => normalizeVisibleText(warning));
  const extractorNote = processingMetadata?.extractorNote
    ? normalizeVisibleText(processingMetadata.extractorNote)
    : null;
  const isRawFallback = processingMetadata?.provenance === 'RAW_FALLBACK';

  return (
    <div style={{ padding: '8px 0' }}>
      {extractorNote && (
        <div
          style={{
            padding: '6px 10px',
            marginBottom: 10,
            background: 'rgba(59,130,246,0.08)',
            border: '1px solid rgba(59,130,246,0.15)',
            borderRadius: 6,
            fontSize: 12,
            color: 'var(--ion-color-primary)',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <IonIcon icon={informationCircleOutline} style={{ fontSize: 12 }} />
          {extractorNote}
        </div>
      )}

      {warnings.length > 0 && (
        <div style={{ display: 'grid', gap: 6, marginBottom: 10 }}>
          {warnings.map((warning, index) => (
            <div
              key={`${warning}-${index}`}
              style={{
                padding: '6px 10px',
                background: 'rgba(217,119,6,0.08)',
                border: '1px solid rgba(217,119,6,0.15)',
                borderRadius: 6,
                fontSize: 12,
                color: 'var(--ion-color-warning)',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
            >
              <IonIcon icon={warningOutline} style={{ fontSize: 12 }} />
              {warning}
            </div>
          ))}
        </div>
      )}

      {parseError && (
        <div
          style={{
            padding: '6px 10px',
            marginBottom: 10,
            background: 'rgba(239,68,68,0.08)',
            border: '1px solid rgba(239,68,68,0.15)',
            borderRadius: 6,
            fontSize: 12,
            color: 'var(--ion-color-danger)',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <IonIcon icon={warningOutline} style={{ fontSize: 12 }} />
          {parseError}
        </div>
      )}

      {events.length > 0 ? (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11, color: 'var(--chat-text)' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--chat-border)' }}>
                <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--chat-muted-2)', whiteSpace: 'nowrap' }}>Type</th>
                <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--chat-muted-2)', whiteSpace: 'nowrap' }}>Code</th>
                <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--chat-muted-2)', whiteSpace: 'nowrap' }}>Timestamp</th>
                <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--chat-muted-2)', whiteSpace: 'nowrap' }}>Device</th>
                <th style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, color: 'var(--chat-muted-2)', whiteSpace: 'nowrap' }}>Error</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event, index) => (
                <tr key={index} style={{ borderBottom: '1px solid var(--chat-border)' }}>
                  <td style={{ padding: '5px 8px', whiteSpace: 'nowrap' }}>{event.type}</td>
                  <td style={{ padding: '5px 8px', fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{event.code}</td>
                  <td style={{ padding: '5px 8px', whiteSpace: 'nowrap' }}>{event.timestamp}</td>
                  <td style={{ padding: '5px 8px', fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{event.deviceId}</td>
                  <td style={{ padding: '5px 8px', whiteSpace: 'nowrap' }}>{event.errorCode}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <IonText color="medium" style={{ fontSize: 12, display: 'block', padding: '8px 0' }}>
          <IonIcon icon={documentTextOutline} style={{ marginRight: 4, fontSize: 12 }} />
          {isRawFallback
            ? 'XML was detected, but the structure did not match the supported event schema.'
            : 'XML trace data is available, but no structured event rows were recovered.'}
        </IonText>
      )}
    </div>
  );
};

interface Props {
  result: JavaSiconiaResult | null;
}

export const SiconiaResultPanel: React.FC<Props> = ({ result }) => {
  if (!result) return null;

  const hasAlarms = Boolean(result.alarmResults && result.alarmResults.length > 0);
  const hasLog = Boolean(result.logAnalysis);
  const hasXml = Boolean(result.xmlTrace);
  const processingMetadata = result.processingMetadata;

  if (!hasAlarms && !hasLog && !hasXml) {
    return null;
  }

  const alarmCount = hasAlarms ? result.alarmResults!.length : 0;
  const defaultExpanded: string[] = [];
  if (hasAlarms) defaultExpanded.push('alarms');
  if (!hasAlarms && hasLog) defaultExpanded.push('log');

  return (
    <IonCard style={{ margin: 0 }}>
      <IonCardContent style={{ padding: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
          <IonIcon icon={informationCircleOutline} style={{ fontSize: 14, color: 'var(--ion-color-primary)' }} />
          <IonText style={{ fontSize: 13, fontWeight: 600, color: 'var(--chat-text)' }}>
            SICONIA Analysis
          </IonText>
          <IonBadge color="medium" style={{ fontSize: 10 }}>
            {normalizeVisibleText(result.inputClass || 'unknown')}
          </IonBadge>
          {processingMetadata?.provenance && (
            <IonBadge color={PROVENANCE_BADGE_COLORS[processingMetadata.provenance]} style={{ fontSize: 10 }}>
              {PROVENANCE_LABELS[processingMetadata.provenance]}
            </IonBadge>
          )}
          {hasAlarms && (
            <IonBadge color="danger" style={{ fontSize: 10 }}>
              {alarmCount} alarm{alarmCount > 1 ? 's' : ''}
            </IonBadge>
          )}
          {hasLog && (
            <IonBadge color={LAYER_COLORS[result.logAnalysis!.dominantLayer] || 'tertiary'} style={{ fontSize: 10 }}>
              {normalizeVisibleText(result.logAnalysis!.dominantLayer)}
            </IonBadge>
          )}
        </div>

        <IonAccordionGroup multiple value={defaultExpanded}>
          {hasAlarms && (
            <IonAccordion value="alarms">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  {alarmCount > 1 ? (
                    <span>Multiple Active Alarms ({alarmCount})</span>
                  ) : (
                    <span>Decoded Alarms</span>
                  )}
                  <IonBadge color="danger" style={{ marginLeft: 4, fontSize: 10 }}>
                    {alarmCount}
                  </IonBadge>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                {result.alarmResults!.map((alarm, index) => (
                  <AlarmCard key={index} alarm={alarm} />
                ))}
              </div>
            </IonAccordion>
          )}

          {hasLog && (
            <IonAccordion value="log">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span>Log Classification</span>
                  <IonBadge color={LAYER_COLORS[result.logAnalysis!.dominantLayer] || 'medium'} style={{ marginLeft: 4, fontSize: 10 }}>
                    {normalizeVisibleText(result.logAnalysis!.dominantLayer)}
                  </IonBadge>
                  <SeverityBadge severity={result.logAnalysis!.highestSeverity} />
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <LogAnalysisCard log={result.logAnalysis!} />
              </div>
            </IonAccordion>
          )}

          {hasXml && (
            <IonAccordion value="xml">
              <IonItem slot="header" color="light">
                <IonLabel style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <IonIcon icon={documentTextOutline} style={{ fontSize: 12 }} />
                  <span>XML Trace</span>
                </IonLabel>
              </IonItem>
              <div slot="content" style={{ padding: '8px 16px' }}>
                <XmlTraceCard xml={result.xmlTrace} processingMetadata={processingMetadata} />
              </div>
            </IonAccordion>
          )}
        </IonAccordionGroup>
      </IonCardContent>
    </IonCard>
  );
};
