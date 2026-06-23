/**
 * ToolTestPanel — Interactive panel for testing MCP tools directly from the UI.
 * Provides sample inputs for each tool and displays structured results.
 * Calls individual backend endpoints at /api/mcp/tools/{tool.name}.
 */
import React, { useState, useCallback } from 'react';
import { IonIcon } from '@ionic/react';
import {
  codeSlashOutline, documentTextOutline, flashOutline,
  checkmarkCircle, closeCircle, timeOutline,
  chevronDownOutline, chevronForwardOutline,
  refreshOutline, bugOutline,
} from 'ionicons/icons';
import { McpToolDef, ToolTestResult, McpToolCategory } from '../types';

/* ── Tool definitions ──────────────────────────────────────────────────────── */

const DLMS_TOOLS: McpToolDef[] = [
  {
    name: 'dlms.parse_hdlc',
    category: 'dlms',
    description: 'Parse a raw DLMS/COSEM HDLC frame from hex string',
    sampleInput: '7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E',
    sampleInputLabel: 'Sample HDLC Frame',
    inputPlaceholder: 'Paste HDLC hex frame (starting/ending with 7E)...',
  },
  {
    name: 'dlms.decode_apdu',
    category: 'dlms',
    description: 'Extract LLC header and classify APDU type from information field',
    sampleInput: 'C401C100BE1004800A0601602801FF000000065FF00000008040FF6E',
    sampleInputLabel: 'Sample APDU Info Field',
    inputPlaceholder: 'Paste HDLC information field hex...',
  },
  {
    name: 'dlms.decode_axdr',
    category: 'dlms',
    description: 'Recursively decode AXDR-encoded DLMS attribute data',
    sampleInput: '0A0601602801FF000000065FF00000008040FF6E',
    sampleInputLabel: 'Sample AXDR Data',
    inputPlaceholder: 'Paste AXDR hex data...',
  },
  {
    name: 'dlms.resolve_obis',
    category: 'dlms',
    description: 'Resolve an OBIS code to Interface Class and description',
    sampleInput: '1.0.1.8.0.255',
    sampleInputLabel: 'Sample OBIS Code',
    inputPlaceholder: 'Enter OBIS code (e.g. 1.0.1.8.0.255)...',
  },
];

const SICONIA_TOOLS: McpToolDef[] = [
  {
    name: 'siconia.decode_alarm',
    category: 'siconia',
    description: 'Decode a SICONIA DCU alarm code to root cause and remediation',
    sampleInput: '0x1342',
    sampleInputLabel: 'Sample Alarm Code',
    inputPlaceholder: 'Enter alarm code (e.g. 0x1342)...',
  },
  {
    name: 'siconia.parse_xml',
    category: 'siconia',
    description: 'Parse a SICONIA HES/DCU XML trace into structured events',
    sampleInput: '<trace><event type="ALARM" code="0x1342" timestamp="2024-01-15T10:30:00Z" deviceId="DCU-001" errorCode="E001"/></trace>',
    sampleInputLabel: 'Sample XML Trace',
    inputPlaceholder: 'Paste SICONIA XML trace with <event> elements...',
  },
  {
    name: 'siconia.classify_log',
    category: 'siconia',
    description: 'Classify a multi-line DCU/HES log block',
    sampleInput: '2024-01-15 10:30:00 [PLC] [ERROR] Connection lost to meter\n2024-01-15 10:30:01 [WAN] [WARN] Retry attempt 1\n2024-01-15 10:30:05 [PLC] [INFO] Reconnection successful',
    sampleInputLabel: 'Sample Log Block',
    inputPlaceholder: 'Paste DCU/HES log content...',
  },
];

const ALL_TOOLS = [...DLMS_TOOLS, ...SICONIA_TOOLS];

/* ── JSON Renderer ─────────────────────────────────────────────────────────── */

const JsonView: React.FC<{ data: unknown; depth?: number }> = ({ data, depth = 0 }) => {
  const indent = depth * 14;

  if (data === null || data === undefined) {
    return <span style={{ color: 'var(--chat-muted-2)', fontStyle: 'italic' }}>null</span>;
  }

  if (typeof data === 'string') {
    return <span style={{ color: 'var(--ion-color-success)', wordBreak: 'break-all' }}>"{data}"</span>;
  }

  if (typeof data === 'number' || typeof data === 'boolean') {
    return <span style={{ color: 'var(--ion-color-tertiary)' }}>{String(data)}</span>;
  }

  if (Array.isArray(data)) {
    if (data.length === 0) return <span style={{ color: 'var(--chat-muted-2)' }}>[]</span>;
    return (
      <div>
        <span style={{ color: 'var(--chat-muted)' }}>[</span>
        {data.map((item, i) => (
          <div key={i} style={{ marginLeft: indent + 14 }}>
            <JsonView data={item} depth={depth + 1} />
            {i < data.length - 1 && <span style={{ color: 'var(--chat-muted-2)' }}>,</span>}
          </div>
        ))}
        <div style={{ marginLeft: indent }}>
          <span style={{ color: 'var(--chat-muted)' }}>]</span>
        </div>
      </div>
    );
  }

  if (typeof data === 'object') {
    const entries = Object.entries(data as Record<string, unknown>);
    if (entries.length === 0) return <span style={{ color: 'var(--chat-muted-2)' }}>{'{}'}</span>;
    return (
      <div>
        <span style={{ color: 'var(--chat-muted)' }}>{'{'}</span>
        {entries.map(([key, val]) => (
          <div key={key} style={{ marginLeft: indent + 14, marginBottom: 2 }}>
            <span style={{ color: 'var(--ion-color-primary)', fontWeight: 500 }}>"{key}"</span>
            <span style={{ color: 'var(--chat-muted)' }}>: </span>
            <JsonView data={val} depth={depth + 1} />
          </div>
        ))}
        <div style={{ marginLeft: indent }}>
          <span style={{ color: 'var(--chat-muted)' }}>{'}'}</span>
        </div>
      </div>
    );
  }

  return <span>{String(data)}</span>;
};

/* ── Result Card ───────────────────────────────────────────────────────────── */

const ResultCard: React.FC<{ result: ToolTestResult }> = ({ result }) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <div style={{
      marginTop: 12, borderRadius: 10, overflow: 'hidden',
      border: `1px solid ${result.success ? 'var(--ion-color-success-shade)' : 'var(--ion-color-danger-shade)'}`,
      animation: 'slideUp 0.25s ease',
    }}>
      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '10px 14px',
        background: result.success ? 'rgba(22,163,74,0.08)' : 'rgba(220,38,38,0.08)',
        cursor: 'pointer',
      }}
        onClick={() => setExpanded(e => !e)}
      >
        <IonIcon
          icon={result.success ? checkmarkCircle : closeCircle}
          style={{ color: result.success ? 'var(--ion-color-success)' : 'var(--ion-color-danger)', fontSize: 18, flexShrink: 0 }}
        />
        <span style={{ fontSize: 13, fontWeight: 600, flex: 1, color: 'var(--chat-text)' }}>
          {result.toolName}
        </span>
        <span style={{ fontSize: 11, color: 'var(--chat-muted)', display: 'flex', alignItems: 'center', gap: 3 }}>
          <IonIcon icon={timeOutline} style={{ fontSize: 12 }} />
          {result.durationMs}ms
        </span>
        <IonIcon
          icon={expanded ? chevronDownOutline : chevronForwardOutline}
          style={{ fontSize: 14, color: 'var(--chat-muted)' }}
        />
      </div>

      {/* Body */}
      {expanded && (
        <div style={{ padding: '12px 14px', background: 'var(--chat-tool-bg)', fontSize: 12, lineHeight: 1.6, maxHeight: 400, overflow: 'auto' }}>
          {result.error ? (
            <div style={{ color: 'var(--ion-color-danger)' }}>
              <strong>Error:</strong> {result.error}
            </div>
          ) : (
            <JsonView data={result.result} />
          )}
        </div>
      )}
    </div>
  );
};

/* ── Main Component ────────────────────────────────────────────────────────── */

interface Props {
  apiKey: string | null;
  jwtToken: string | null;
  onClose: () => void;
}

export const ToolTestPanel: React.FC<Props> = ({ apiKey, jwtToken, onClose }) => {
  const [activeCategory, setActiveCategory] = useState<McpToolCategory>('dlms');
  const [inputs, setInputs] = useState<Record<string, string>>({});
  const [results, setResults] = useState<ToolTestResult[]>([]);
  const [testing, setTesting] = useState<string | null>(null);

  const filteredTools = ALL_TOOLS.filter(t => t.category === activeCategory);

  const handleRun = useCallback(async (tool: McpToolDef) => {
    const input = inputs[tool.name]?.trim() || tool.sampleInput;
    if (!input) return;

    setTesting(tool.name);
    const start = performance.now();

    try {
      const authHeader: Record<string, string> = jwtToken
        ? { Authorization: `Bearer ${jwtToken}` }
        : { 'X-API-Key': apiKey || '' };

      // Call the individual tool endpoint with flat args
      const res = await fetch(`/api/mcp/tools/${tool.name}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...authHeader,
        },
        body: JSON.stringify({
          [getArgName(tool)]: input,
        }),
      });

      const duration = Math.round(performance.now() - start);

      if (res.ok) {
        const data = await res.json();
        setResults(prev => [{
          toolName: tool.name,
          success: true,
          durationMs: duration,
          result: data,
        }, ...prev]);
      } else {
        const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` }));
        setResults(prev => [{
          toolName: tool.name,
          success: false,
          durationMs: duration,
          result: null,
          error: err.error || `HTTP ${res.status}`,
        }, ...prev]);
      }
    } catch (err) {
      const duration = Math.round(performance.now() - start);
      setResults(prev => [{
        toolName: tool.name,
        success: false,
        durationMs: duration,
        result: null,
        error: err instanceof Error ? err.message : 'Network error',
      }, ...prev]);
    } finally {
      setTesting(null);
    }
  }, [apiKey, jwtToken, inputs]);

  const handleRunAll = useCallback(async () => {
    for (const tool of filteredTools) {
      await handleRun(tool);
    }
  }, [filteredTools, handleRun]);

  const clearResults = () => setResults([]);

  return (
    <div style={{
      position: 'fixed', top: 0, right: 0, bottom: 0, width: 420,
      background: 'var(--chat-bg)',
      borderLeft: `1px solid var(--chat-border)`,
      zIndex: 500,
      display: 'flex', flexDirection: 'column',
      boxShadow: 'var(--chat-shadow-lg)',
      animation: 'slideIn 0.25s ease',
    }}>
      {/* Header */}
      <div style={{
        padding: '16px 16px 12px',
        borderBottom: `1px solid var(--chat-border)`,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{
              width: 28, height: 28, borderRadius: 8,
              background: 'linear-gradient(135deg, var(--chat-gradient-start), var(--chat-gradient-end))',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <IonIcon icon={bugOutline} style={{ fontSize: 16, color: '#fff' }} />
            </div>
            <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--chat-text)' }}>
              Tool Testing
            </span>
          </div>
          <button
            onClick={onClose}
            style={{
              width: 28, height: 28, borderRadius: 8, border: 'none',
              background: 'transparent', cursor: 'pointer', fontSize: 16,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: 'var(--chat-muted)',
            }}
          >✕</button>
        </div>

        {/* Category tabs */}
        <div style={{ display: 'flex', gap: 6 }}>
          {(['dlms', 'siconia'] as McpToolCategory[]).map(cat => (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              style={{
                flex: 1, padding: '7px 12px', borderRadius: 8,
                border: `1px solid ${activeCategory === cat ? 'var(--ion-color-primary)' : 'var(--chat-border)'}`,
                background: activeCategory === cat ? 'var(--chat-primary-soft)' : 'transparent',
                color: activeCategory === cat ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                fontSize: 12, fontWeight: 600, cursor: 'pointer',
                textTransform: 'uppercase', letterSpacing: '0.5px',
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
              }}
            >
              <IonIcon icon={cat === 'dlms' ? codeSlashOutline : documentTextOutline} style={{ fontSize: 14 }} />
              {cat}
            </button>
          ))}
        </div>
      </div>

      {/* Tool list */}
      <div style={{ flex: 1, overflowY: 'auto', padding: 12 }}>
        {filteredTools.map(tool => (
          <div key={tool.name} style={{
            marginBottom: 12, padding: 12, borderRadius: 10,
            border: `1px solid var(--chat-border)`,
            background: 'var(--chat-surface)',
          }}>
            <div style={{ marginBottom: 8 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--chat-text)', marginBottom: 2 }}>
                {tool.name}
              </div>
              <div style={{ fontSize: 11, color: 'var(--chat-muted)' }}>
                {tool.description}
              </div>
            </div>

            <textarea
              value={inputs[tool.name] ?? ''}
              onChange={e => setInputs(prev => ({ ...prev, [tool.name]: e.target.value }))}
              placeholder={tool.inputPlaceholder}
              rows={2}
              style={{
                width: '100%', padding: '8px 10px', borderRadius: 8,
                border: `1px solid var(--chat-border)`,
                background: 'var(--chat-input-bg)',
                color: 'var(--chat-text)',
                fontSize: 12, fontFamily: 'monospace',
                resize: 'vertical', outline: 'none',
                boxSizing: 'border-box',
              }}
            />

            <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
              <button
                onClick={() => setInputs(prev => ({ ...prev, [tool.name]: tool.sampleInput }))}
                style={{
                  padding: '4px 10px', borderRadius: 6, fontSize: 11,
                  border: `1px solid var(--chat-border)`,
                  background: 'transparent', color: 'var(--chat-muted)',
                  cursor: 'pointer', fontWeight: 500,
                }}
              >
                <IonIcon icon={flashOutline} style={{ fontSize: 11, marginRight: 3 }} />
                {tool.sampleInputLabel}
              </button>
              <button
                onClick={() => handleRun(tool)}
                disabled={testing === tool.name}
                style={{
                  marginLeft: 'auto', padding: '4px 14px', borderRadius: 6, fontSize: 12,
                  border: 'none',
                  background: testing === tool.name ? 'var(--chat-muted-2)' : 'var(--ion-color-primary)',
                  color: '#fff', cursor: testing === tool.name ? 'not-allowed' : 'pointer',
                  fontWeight: 600, display: 'flex', alignItems: 'center', gap: 4,
                }}
              >
                {testing === tool.name ? (
                  <span style={{ width: 12, height: 12, border: '2px solid rgba(255,255,255,0.3)', borderTopColor: '#fff', borderRadius: '50%', display: 'inline-block', animation: 'spin 0.7s linear infinite' }} />
                ) : (
                  <IonIcon icon={refreshOutline} style={{ fontSize: 12 }} />
                )}
                {testing === tool.name ? 'Running...' : 'Run'}
              </button>
            </div>
          </div>
        ))}

        {/* Run All button */}
        {filteredTools.length > 0 && (
          <button
            onClick={handleRunAll}
            disabled={testing !== null}
            style={{
              width: '100%', padding: '10px', borderRadius: 10, fontSize: 13,
              border: `1.5px dashed var(--ion-color-primary)`,
              background: 'var(--chat-primary-soft)', color: 'var(--ion-color-primary)',
              cursor: testing !== null ? 'not-allowed' : 'pointer',
              fontWeight: 600, marginBottom: 12,
            }}
          >
            Run All {activeCategory.toUpperCase()} Tools
          </button>
        )}

        {/* Results */}
        {results.length > 0 && (
          <div>
            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              marginBottom: 8,
            }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--chat-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
                Results ({results.length})
              </span>
              <button
                onClick={clearResults}
                style={{
                  padding: '2px 8px', borderRadius: 6, fontSize: 11,
                  border: `1px solid var(--chat-border)`,
                  background: 'transparent', color: 'var(--chat-muted)',
                  cursor: 'pointer',
                }}
              >
                Clear
              </button>
            </div>
            {results.map((r, i) => (
              <ResultCard key={`${r.toolName}-${i}`} result={r} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

/* ── Helpers ────────────────────────────────────────────────────────────────── */

/**
 * Maps tool names to their expected argument name on the backend endpoint.
 * Must match the @RequestBody key used in McpToolController.java.
 */
function getArgName(tool: McpToolDef): string {
  const map: Record<string, string> = {
    'dlms.parse_hdlc': 'frame_hex',
    'dlms.decode_apdu': 'information_hex',
    'dlms.decode_axdr': 'axdr_hex',
    'dlms.resolve_obis': 'obis_str',
    'siconia.decode_alarm': 'alarm_code',
    'siconia.parse_xml': 'xml_text',
    'siconia.classify_log': 'log_text',
  };
  return map[tool.name] || 'input';
}
