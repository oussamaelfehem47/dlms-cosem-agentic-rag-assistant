import React from 'react';
import { IonBadge, IonCard, IonCardContent, IonText } from '@ionic/react';
import { JavaProfileResult } from '../types';
import { Field } from './DecodeTreePanel';

interface Props {
  result: JavaProfileResult | null;
}

export const ProfileResultPanel: React.FC<Props> = ({ result }) => {
  if (!result) return null;

  return (
    <IonCard style={{ margin: 0 }}>
      <IonCardContent style={{ padding: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
          <IonText style={{ fontSize: 13, fontWeight: 600, color: 'var(--chat-text)' }}>
            Profile Result
          </IonText>
          <IonBadge color="tertiary" style={{ fontSize: 10 }}>
            {result.profileType}
          </IonBadge>
          <IonBadge color="medium" style={{ fontSize: 10 }}>
            {result.captureObjectCount} columns
          </IonBadge>
          <IonBadge color="primary" style={{ fontSize: 10 }}>
            {result.entryCount} rows
          </IonBadge>
        </div>

        <div style={{ marginBottom: 12 }}>
          <Field label="Profile Type" value={result.profileType} />
          <Field label="Profile OBIS" value={result.obis || '—'} mono />
          <Field label="Capture Objects" value={result.captureObjectCount} />
          <Field label="Entry Count" value={result.entryCount} />
        </div>

        {result.columns.length > 0 && (
          <div style={{ marginBottom: 12 }}>
            <IonText style={{ display: 'block', fontSize: 12, fontWeight: 700, marginBottom: 8, color: 'var(--chat-text)' }}>
              Capture Objects
            </IonText>
            <div style={{ overflowX: 'auto', border: '1px solid var(--chat-border)', borderRadius: 8 }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead>
                  <tr style={{ background: 'var(--chat-surface)' }}>
                    <th style={thStyle}>#</th>
                    <th style={thStyle}>OBIS</th>
                    <th style={thStyle}>Description</th>
                    <th style={thStyle}>Class</th>
                    <th style={thStyle}>Attr</th>
                    <th style={thStyle}>Unit</th>
                    <th style={thStyle}>Scaler</th>
                  </tr>
                </thead>
                <tbody>
                  {result.columns.map((column) => (
                    <tr key={`${column.index}-${column.obis}`}>
                      <td style={tdStyle}>{column.index}</td>
                      <td style={{ ...tdStyle, fontFamily: 'monospace' }}>{column.obis}</td>
                      <td style={tdStyle}>{column.description || '—'}</td>
                      <td style={tdStyle}>{column.classId}</td>
                      <td style={tdStyle}>{column.attributeIndex}</td>
                      <td style={tdStyle}>{column.unit || '—'}</td>
                      <td style={tdStyle}>{column.scaler ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {result.rows.length > 0 && (
          <div>
            <IonText style={{ display: 'block', fontSize: 12, fontWeight: 700, marginBottom: 8, color: 'var(--chat-text)' }}>
              Profile Data
            </IonText>
            <div style={{ overflowX: 'auto', border: '1px solid var(--chat-border)', borderRadius: 8 }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead>
                  <tr style={{ background: 'var(--chat-surface)' }}>
                    <th style={thStyle}>Timestamp</th>
                    {result.columns.map((column) => (
                      <th key={`col-${column.index}`} style={thStyle}>
                        {column.obis}
                      </th>
                    ))}
                    {result.columns.length === 0 && <th style={thStyle}>Values</th>}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((row, rowIndex) => (
                    <tr key={`row-${rowIndex}`}>
                      <td style={tdStyle}>{row.timestamp || '—'}</td>
                      {row.cells.map((cell, cellIndex) => (
                        <td key={`row-${rowIndex}-cell-${cellIndex}`} style={tdStyle}>
                          {cell.displayString}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </IonCardContent>
    </IonCard>
  );
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '8px 10px',
  borderBottom: '1px solid var(--chat-border)',
  color: 'var(--chat-muted-2)',
  fontWeight: 700,
  whiteSpace: 'nowrap',
};

const tdStyle: React.CSSProperties = {
  padding: '8px 10px',
  borderBottom: '1px solid var(--chat-border)',
  color: 'var(--chat-text)',
  verticalAlign: 'top',
  whiteSpace: 'nowrap',
};
