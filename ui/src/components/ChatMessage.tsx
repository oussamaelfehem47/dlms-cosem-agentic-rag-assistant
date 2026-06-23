import React, { useCallback, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { IonIcon } from '@ionic/react';
import {
  checkmarkOutline,
  chevronDownOutline,
  chevronForwardOutline,
  copyOutline,
  thumbsDownOutline,
  thumbsDownSharp,
  thumbsUpOutline,
  thumbsUpSharp,
  warningOutline,
} from 'ionicons/icons';
import {
  DecodeResult,
  InputClass,
  JavaArtifactResult,
  JavaDecodeResult,
  JavaOrchestrationMode,
  JavaExplanationMode,
  JavaStrategyMetadata,
  JavaDlmsProvenance,
  JavaSiconiaProvenance,
  JavaSiconiaResult,
  JavaToolTraceEntry,
  JavaToolProvenance,
  SiconiaAnalysis,
} from '../types';
import {
  compactGreetingReply,
  getDecodeInterpretationSummary,
  getDecodeFailureSummary,
  getSiconiaInterpretationSummary,
  hasInvalidFcs,
  formatStructuredNarrationForMarkdown,
  normalizeTechnicalReply,
  type AssistantInterpretationSummary,
  stripRedundantStructuredExplanation,
} from '../chat/assistantMessageUtils';
import { extractTrailingSources } from '../chat/citationUtils';
import { CATEGORY_ICONS } from '../chat/chatConfig';
import { UiConversationCategory } from '../chat/chatFeatureUtils';
import { normalizeVisibleBlock, normalizeVisibleText } from '../chat/textNormalization';
import { DecodeTree } from './DecodeTree';
import { DecodeTreePanel } from './DecodeTreePanel';
import { ProfileResultPanel } from './ProfileResultPanel';
import { SiconiaResultPanel } from './SiconiaResultPanel';

function isJavaDecodeResult(value: unknown): value is JavaDecodeResult {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.apduType === 'string' || typeof candidate.hdlcFrame === 'object';
}

function isJavaSiconiaResult(value: unknown): value is JavaSiconiaResult {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return Array.isArray(candidate.alarmResults)
    || typeof candidate.logAnalysis === 'object'
    || typeof candidate.xmlTrace === 'object'
    || typeof candidate.processingMetadata === 'object';
}

const SICONIA_PROVENANCE_LABELS: Record<JavaSiconiaProvenance, string> = {
  STRUCTURED_DIRECT: 'Structured',
  STRUCTURED_HEURISTIC: 'Heuristic',
  RAW_FALLBACK: 'Raw fallback',
};

const SICONIA_PROVENANCE_COLORS: Record<JavaSiconiaProvenance, React.CSSProperties> = {
  STRUCTURED_DIRECT: {
    background: 'rgba(34,197,94,0.1)',
    color: 'var(--ion-color-success)',
    border: '1px solid rgba(34,197,94,0.2)',
  },
  STRUCTURED_HEURISTIC: {
    background: 'rgba(217,119,6,0.1)',
    color: 'var(--ion-color-warning)',
    border: '1px solid rgba(217,119,6,0.2)',
  },
  RAW_FALLBACK: {
    background: 'rgba(107,114,128,0.1)',
    color: 'var(--chat-muted)',
    border: '1px solid var(--chat-border)',
  },
};

const DLMS_PROVENANCE_LABELS: Record<JavaDlmsProvenance, string> = {
  STRUCTURED_DIRECT: 'Structured',
  STRUCTURED_HEURISTIC: 'Heuristic',
  RAW_FALLBACK: 'Raw fallback',
};

const DLMS_PROVENANCE_COLORS: Record<JavaDlmsProvenance, React.CSSProperties> = {
  STRUCTURED_DIRECT: {
    background: 'rgba(34,197,94,0.1)',
    color: 'var(--ion-color-success)',
    border: '1px solid rgba(34,197,94,0.2)',
  },
  STRUCTURED_HEURISTIC: {
    background: 'rgba(59,130,246,0.1)',
    color: 'var(--ion-color-primary)',
    border: '1px solid rgba(59,130,246,0.2)',
  },
  RAW_FALLBACK: {
    background: 'rgba(107,114,128,0.1)',
    color: 'var(--chat-muted)',
    border: '1px solid var(--chat-border)',
  },
};

const EXPLANATION_MODE_LABELS: Record<JavaExplanationMode, string> = {
  DETERMINISTIC_ONLY: 'Deterministic only',
  GROUNDED_LLM: 'Grounded explanation',
  TENTATIVE_GROUNDED: 'Tentative explanation',
};

const EXPLANATION_MODE_STYLES: Record<JavaExplanationMode, React.CSSProperties> = {
  DETERMINISTIC_ONLY: {
    background: 'rgba(107,114,128,0.1)',
    color: 'var(--chat-muted)',
    border: '1px solid var(--chat-border)',
  },
  GROUNDED_LLM: {
    background: 'rgba(59,130,246,0.1)',
    color: 'var(--ion-color-primary)',
    border: '1px solid rgba(59,130,246,0.2)',
  },
  TENTATIVE_GROUNDED: {
    background: 'rgba(217,119,6,0.1)',
    color: 'var(--ion-color-warning)',
    border: '1px solid rgba(217,119,6,0.2)',
  },
};

const TOOL_PROVENANCE_LABELS: Record<JavaToolProvenance, string> = {
  MCP: 'MCP',
  JAVA: 'Java',
  MIXED: 'Mixed',
};

const TOOL_PROVENANCE_STYLES: Record<JavaToolProvenance, React.CSSProperties> = {
  MCP: {
    background: 'rgba(16,185,129,0.1)',
    color: 'var(--ion-color-success)',
    border: '1px solid rgba(16,185,129,0.2)',
  },
  JAVA: {
    background: 'rgba(99,102,241,0.1)',
    color: 'rgb(129,140,248)',
    border: '1px solid rgba(99,102,241,0.2)',
  },
  MIXED: {
    background: 'rgba(8,145,178,0.1)',
    color: 'var(--ion-color-secondary)',
    border: '1px solid rgba(8,145,178,0.2)',
  },
};

const ORCHESTRATION_MODE_LABELS: Record<JavaOrchestrationMode, string> = {
  DETERMINISTIC_FAST_PATH: 'Deterministic fast path',
  STRUCTURED_PLUS_AGENTIC: 'Structured + agentic',
  NATURAL_LANGUAGE_AGENTIC: 'Natural-language agentic',
  AMBIGUOUS_SAFE_FALLBACK: 'Ambiguous safe fallback',
};

function trustSummary(
  explanationMode?: JavaExplanationMode | null,
  toolProvenance?: JavaToolProvenance | null,
): string | null {
  if (explanationMode === 'TENTATIVE_GROUNDED') {
    return 'Only the trustworthy outer role is explained. Payload interpretation stays untrusted.';
  }
  if (explanationMode === 'DETERMINISTIC_ONLY') {
    return 'The answer came from deterministic backend logic without a grounded LLM explanation pass.';
  }
  if (explanationMode === 'GROUNDED_LLM') {
    if (toolProvenance === 'MIXED') {
      return 'Deterministic results were treated as authoritative and then enriched with grounded retrieval context.';
    }
    if (toolProvenance === 'MCP') {
      return 'Authoritative MCP-backed structured results were explained with grounded answer shaping.';
    }
    if (toolProvenance === 'JAVA') {
      return 'Authoritative deterministic Java parsing was explained with grounded answer shaping.';
    }
    return 'Grounded answer shaping was used on top of authoritative backend results.';
  }
  if (toolProvenance === 'MIXED') {
    return 'The answer combines deterministic backend truth with retrieval-backed context.';
  }
  if (toolProvenance === 'MCP') {
    return 'The answer is anchored in MCP-backed deterministic tool output.';
  }
  if (toolProvenance === 'JAVA') {
    return 'The answer is anchored in deterministic Java parsing and backend rules.';
  }
  return null;
}

const BADGE: Record<InputClass, { label: string; bg: string; color: string; border: string; icon: string }> = {
  hex_frame: { label: 'HEX FRAME', bg: 'var(--chat-primary-soft)', color: 'var(--ion-color-primary)', border: 'var(--chat-border)', icon: 'HD' },
  xml_trace: { label: 'XML TRACE', bg: 'rgba(124,58,237,0.08)', color: 'var(--ion-color-tertiary)', border: 'var(--chat-border)', icon: 'XML' },
  alarm_code: { label: 'ALARM CODE', bg: 'rgba(217,119,6,0.08)', color: 'var(--ion-color-warning)', border: 'var(--chat-border)', icon: 'AL' },
  log_block: { label: 'LOG BLOCK', bg: 'rgba(8,145,178,0.08)', color: 'var(--ion-color-secondary)', border: 'var(--chat-border)', icon: 'LOG' },
  query: { label: 'QUERY', bg: 'var(--chat-surface)', color: 'var(--chat-muted)', border: 'var(--chat-border)', icon: 'Q' },
  unknown: { label: 'UNKNOWN', bg: 'var(--chat-surface)', color: 'var(--chat-muted)', border: 'var(--chat-border)', icon: '?' },
};

const SEVERITY_BADGE_COLORS: Record<string, { bg: string; color: string; border: string }> = {
  CRITICAL: { bg: 'rgba(220,38,38,0.15)', color: 'rgb(248,113,113)', border: 'rgba(220,38,38,0.3)' },
  HIGH: { bg: 'rgba(239,68,68,0.12)', color: 'rgb(248,113,113)', border: 'rgba(239,68,68,0.25)' },
  MEDIUM: { bg: 'rgba(217,119,6,0.12)', color: 'rgb(250,204,21)', border: 'rgba(217,119,6,0.25)' },
  LOW: { bg: 'rgba(107,114,128,0.1)', color: 'var(--chat-muted)', border: 'var(--chat-border)' },
  INFO: { bg: 'rgba(59,130,246,0.1)', color: 'var(--ion-color-primary)', border: 'rgba(59,130,246,0.2)' },
};

const LAYER_BADGE_COLORS: Record<string, { bg: string; color: string; border: string }> = {
  WAN: { bg: 'rgba(59,130,246,0.12)', color: 'var(--ion-color-primary)', border: 'rgba(59,130,246,0.25)' },
  PLC: { bg: 'rgba(217,119,6,0.12)', color: 'var(--ion-color-warning)', border: 'rgba(217,119,6,0.25)' },
  RF: { bg: 'rgba(124,58,237,0.12)', color: 'var(--ion-color-tertiary)', border: 'rgba(124,58,237,0.25)' },
  HES: { bg: 'rgba(239,68,68,0.12)', color: 'var(--ion-color-danger)', border: 'rgba(239,68,68,0.25)' },
  DLMS: { bg: 'rgba(34,197,94,0.12)', color: 'var(--ion-color-success)', border: 'rgba(34,197,94,0.25)' },
};

function getDecodeBadge(javaDecode: JavaDecodeResult | null): string {
  if (!javaDecode) return 'Decode - FRAME_DECODE';

  const frame = javaDecode.hdlcFrame;
  const apdu = javaDecode.apduType;
  const normalizedKind = javaDecode.processingMetadata?.normalizedKind;

  if (!frame && normalizedKind === 'OBIS_QUERY') {
    return 'Decode - OBIS';
  }
  if (!frame && normalizedKind === 'AXDR_HEX') {
    return 'Decode - AXDR';
  }
  if (!frame && normalizedKind === 'APDU_HEX' && (!apdu || apdu === 'UNKNOWN')) {
    return 'Decode - APDU';
  }

  if (frame?.frameType === 'U_FRAME') {
    return `Decode - U_FRAME (${frame.uFrameType || 'UNKNOWN'})`;
  }
  if (frame?.frameType === 'S_FRAME') {
    return `Decode - S_FRAME (${frame.sFrameType || 'UNKNOWN'})`;
  }
  if (apdu && apdu !== 'UNKNOWN') {
    return `Decode - ${apdu}`;
  }
  if (frame?.frameType) {
    return `Decode - ${frame.frameType}`;
  }
  return 'Decode - UNKNOWN';
}

function getDlmsProvenancePresentation(javaDecode: JavaDecodeResult | null): { label: string; style: React.CSSProperties } | null {
  const provenance = javaDecode?.processingMetadata?.provenance;
  if (!provenance) return null;

  if (hasInvalidFcs(javaDecode)) {
    return {
      label: 'Tentative',
      style: {
        background: 'rgba(217,119,6,0.1)',
        color: 'var(--ion-color-warning)',
        border: '1px solid rgba(217,119,6,0.2)',
      },
    };
  }

  return {
    label: DLMS_PROVENANCE_LABELS[provenance],
    style: DLMS_PROVENANCE_COLORS[provenance],
  };
}

function formatTime(date: Date): string {
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const mins = Math.floor(diff / 60000);

  if (mins < 1) return 'Just now';
  if (mins < 60) return `${mins}m ago`;

  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;

  return date.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

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
      aria-label={copied ? 'Response copied to clipboard' : 'Copy response'}
      title="Copy response"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '5px 9px',
        borderRadius: 10,
        border: '1px solid var(--chat-border)',
        background: 'var(--chat-surface)',
        cursor: 'pointer',
        color: copied ? 'var(--ion-color-success)' : 'var(--chat-muted-2)',
        fontSize: 11,
        fontFamily: 'inherit',
        transition: 'all 0.15s',
      }}
      onMouseEnter={(event) => {
        if (!copied) event.currentTarget.style.borderColor = 'var(--ion-color-primary)';
      }}
      onMouseLeave={(event) => {
        if (!copied) event.currentTarget.style.borderColor = 'var(--chat-border)';
      }}
    >
      <IonIcon icon={copied ? checkmarkOutline : copyOutline} style={{ fontSize: 12 }} />
      {copied ? 'Copied' : 'Copy'}
    </button>
  );
};

const CITATION_RE = /\[Source:\s*([^\]]+)\]/g;

const SourceList: React.FC<{ citations: string[] }> = ({ citations }) => {
  if (citations.length === 0) return null;
  return (
    <div
      data-testid="assistant-sources"
      style={{ marginTop: 16, paddingTop: 12, borderTop: '1px solid var(--chat-border)' }}
    >
      <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--chat-muted-2)', marginBottom: 8, letterSpacing: '0.05em', textTransform: 'uppercase' }}>
        Sources
      </div>
      {citations.map((citation, index) => (
        <div
          key={citation}
          data-testid="assistant-source-item"
          style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 4, fontSize: 12, color: 'var(--chat-muted)' }}
        >
          <span style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 18,
            height: 18,
            borderRadius: '50%',
            flexShrink: 0,
            background: 'var(--chat-primary-soft)',
            border: '1px solid var(--chat-border)',
            color: 'var(--ion-color-primary)',
            fontSize: 10,
            fontWeight: 700,
          }}>
            {index + 1}
          </span>
          <span>{citation}</span>
        </div>
      ))}
    </div>
  );
};

const CodeBlock: React.FC<{ code: string; language?: string }> = ({ code, language }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(normalizeVisibleBlock(code));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignore clipboard failures.
    }
  }, [code]);

  return (
    <div style={{
      margin: '12px 0',
      borderRadius: 12,
      overflow: 'hidden',
      border: '1px solid var(--chat-code-border)',
      background: 'var(--chat-code-bg)',
    }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '8px 12px',
        background: 'var(--chat-surface)',
        borderBottom: '1px solid var(--chat-code-border)',
      }}>
        <span style={{ fontSize: 11, color: 'var(--chat-muted-2)', fontWeight: 700, fontFamily: 'monospace' }}>
          {language || 'code'}
        </span>
        <button
          type="button"
          onClick={handleCopy}
          aria-label={copied ? 'Code copied to clipboard' : 'Copy code block'}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '3px 8px',
            borderRadius: 8,
            border: '1px solid var(--chat-border)',
            background: 'transparent',
            cursor: 'pointer',
            color: copied ? 'var(--ion-color-success)' : 'var(--chat-muted-2)',
            fontSize: 11,
            fontFamily: 'inherit',
          }}
        >
          <IonIcon icon={copied ? checkmarkOutline : copyOutline} style={{ fontSize: 11 }} />
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre style={{
        margin: 0,
        padding: '12px 16px',
        fontSize: 13,
        lineHeight: 1.6,
        overflowX: 'auto',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        fontFamily: '"Fira Code", "Consolas", "Cascadia Code", monospace',
        color: 'var(--chat-text)',
      }}>
        {code}
      </pre>
    </div>
  );
};

function extractInlineSources(text: string): { bodyText: string; citations: string[] } {
  const citations: string[] = [];
  const seen = new Set<string>();

  const bodyText = text
    .replace(CITATION_RE, (_, citation: string) => {
      const normalizedCitation = citation.trim();
      if (normalizedCitation && !seen.has(normalizedCitation)) {
        seen.add(normalizedCitation);
        citations.push(normalizedCitation);
      }
      return '';
    })
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();

  return { bodyText, citations };
}

const RichText: React.FC<{ text: string }> = ({ text }) => {
  const { bodyText, citations: footerCitations } = extractTrailingSources(text);
  const { bodyText: markdownBody, citations: inlineCitations } = extractInlineSources(bodyText);
  const citations = Array.from(new Set([...inlineCitations, ...footerCitations]));

  return (
    <>
      {markdownBody && (
        <div className="assistant-markdown">
          <ReactMarkdown
            components={{
              p({ children }) {
                return <p>{children}</p>;
              },
              code({ className, children, ...props }) {
                const content = String(children).replace(/\n$/, '');
                const isBlock = typeof className === 'string' && className.startsWith('language-');
                if (isBlock) {
                  const language = className.replace('language-', '');
                  return <CodeBlock code={content} language={language || undefined} />;
                }
                return (
                  <code className="inline-code" {...props}>
                    {children}
                  </code>
                );
              },
              pre({ children }) {
                return <>{children}</>;
              },
              a({ href, children }) {
                return <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>;
              },
            }}
          >
            {markdownBody}
          </ReactMarkdown>
        </div>
      )}
      <SourceList citations={citations} />
    </>
  );
};

function normalizeArtifactInputClass(inputClass?: string | null): InputClass {
  if (!inputClass) return 'query';
  const normalized = inputClass.toLowerCase();
  if (normalized === 'hex_frame' || normalized === 'xml_trace' || normalized === 'alarm_code' || normalized === 'log_block' || normalized === 'query') {
    return normalized;
  }
  return 'query';
}

function artifactBadgePresentation(
  artifact: JavaArtifactResult,
  javaDecode: JavaDecodeResult | null,
  javaSiconia: JavaSiconiaResult | null,
): { label: string; bg: string; color: string; border: string } {
  if (javaDecode?.hdlcFrame) {
    return BADGE.hex_frame;
  }

  const normalizedKind = javaDecode?.processingMetadata?.normalizedKind;
  if (normalizedKind === 'AXDR_HEX') {
    return { label: 'AXDR', bg: 'rgba(8,145,178,0.08)', color: 'var(--ion-color-secondary)', border: 'var(--chat-border)' };
  }
  if (normalizedKind === 'APDU_HEX') {
    return { label: 'APDU', bg: 'rgba(59,130,246,0.08)', color: 'var(--ion-color-primary)', border: 'var(--chat-border)' };
  }
  if (normalizedKind === 'OBIS_QUERY') {
    return { label: 'OBIS', bg: 'rgba(34,197,94,0.08)', color: 'var(--ion-color-success)', border: 'var(--chat-border)' };
  }

  if (javaSiconia?.alarmResults?.length) {
    return BADGE.alarm_code;
  }
  if (javaSiconia?.logAnalysis) {
    return BADGE.log_block;
  }
  if (javaSiconia?.xmlTrace) {
    return BADGE.xml_trace;
  }

  return BADGE[normalizeArtifactInputClass(artifact.inputClass)];
}

const ArtifactSection: React.FC<{ artifact: JavaArtifactResult }> = ({ artifact }) => {
  const javaDecode = artifact.decodeResult && isJavaDecodeResult(artifact.decodeResult) ? artifact.decodeResult : null;
  const javaSiconia = artifact.siconiaResult && isJavaSiconiaResult(artifact.siconiaResult) ? artifact.siconiaResult : null;
  const stageLabel = javaDecode
    ? normalizeVisibleText(getDecodeBadge(javaDecode))
    : javaSiconia
      ? normalizeVisibleText(`SICONIA - ${javaSiconia.inputClass || 'analysis'}`)
      : normalizeVisibleText(`Artifact ${artifact.index + 1}`);
  const explanation = formatStructuredNarrationForMarkdown(
    normalizeTechnicalReply(compactGreetingReply(artifact.explanation || '')),
  );
  const inputClassBadge = artifactBadgePresentation(artifact, javaDecode, javaSiconia);

  return (
    <section
      data-testid="assistant-artifact-section"
      style={{
        marginTop: 14,
        padding: '12px 12px 10px',
        borderRadius: 16,
        border: '1px solid var(--chat-border)',
        background: 'var(--chat-surface)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
        <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--chat-text)' }}>
          Artifact {artifact.index + 1}
        </span>
        {artifact.filename && (
          <span style={{ fontSize: 11, color: 'var(--chat-muted)' }}>
            {normalizeVisibleText(artifact.filename)}
          </span>
        )}
        <span style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 8px',
          borderRadius: 999,
          border: `1px solid ${inputClassBadge.border}`,
          background: inputClassBadge.bg,
          color: inputClassBadge.color,
          fontSize: 10.5,
          fontWeight: 700,
        }}>
          {inputClassBadge.label}
        </span>
        {artifact.explanationMode && (
          <span style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '4px 8px',
            borderRadius: 999,
            fontSize: 10.5,
            fontWeight: 700,
            ...EXPLANATION_MODE_STYLES[artifact.explanationMode],
          }}>
            {EXPLANATION_MODE_LABELS[artifact.explanationMode]}
          </span>
        )}
        {artifact.toolProvenance && (
          <span style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '4px 8px',
            borderRadius: 999,
            fontSize: 10.5,
            fontWeight: 700,
            ...TOOL_PROVENANCE_STYLES[artifact.toolProvenance],
          }}>
            {TOOL_PROVENANCE_LABELS[artifact.toolProvenance]}
          </span>
        )}
      </div>

      <div style={{ marginBottom: 12, fontSize: 12, fontWeight: 700, color: 'var(--chat-muted)' }}>
        {stageLabel}
      </div>

      {javaDecode && <DecodeTreePanel result={javaDecode} />}
      {javaDecode?.profileResult && <div style={{ marginTop: 8 }}><ProfileResultPanel result={javaDecode.profileResult} /></div>}
      {javaSiconia && <div style={{ marginTop: 8 }}><SiconiaResultPanel result={javaSiconia} /></div>}

      {explanation.trim() && (
        <div style={{ marginTop: 12, fontSize: 14, lineHeight: 1.75, color: 'var(--chat-text)' }}>
          <RichText text={explanation} />
        </div>
      )}
    </section>
  );
};

type FeedbackValue = 'like' | 'dislike';

const FeedbackButton: React.FC<{ onFeedback: (v: FeedbackValue) => void }> = ({ onFeedback }) => {
  const [selected, setSelected] = useState<FeedbackValue | null>(null);

  const handle = (v: FeedbackValue) => {
    if (selected !== null) return;
    setSelected(v);
    onFeedback(v);
  };

  const btnStyle = (v: FeedbackValue): React.CSSProperties => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    padding: '5px 9px',
    borderRadius: 10,
    border: `1px solid ${selected === v ? (v === 'like' ? 'rgba(34,197,94,0.4)' : 'rgba(239,68,68,0.4)') : 'var(--chat-border)'}`,
    background: selected === v ? (v === 'like' ? 'rgba(34,197,94,0.1)' : 'rgba(239,68,68,0.1)') : 'var(--chat-surface)',
    cursor: selected !== null ? 'default' : 'pointer',
    color: selected === v ? (v === 'like' ? 'rgb(34,197,94)' : 'rgb(239,68,68)') : 'var(--chat-muted-2)',
    fontSize: 11,
    fontFamily: 'inherit',
    opacity: selected !== null && selected !== v ? 0.4 : 1,
    transition: 'all 0.15s',
  });

  return (
    <div style={{ display: 'inline-flex', gap: 4 }}>
      <button type="button" style={btnStyle('like')} onClick={() => handle('like')}
        aria-label="Mark response as helpful" title="Helpful">
        <IonIcon icon={selected === 'like' ? thumbsUpSharp : thumbsUpOutline} style={{ fontSize: 12 }} />
      </button>
      <button type="button" style={btnStyle('dislike')} onClick={() => handle('dislike')}
        aria-label="Mark response as not helpful" title="Not helpful">
        <IonIcon icon={selected === 'dislike' ? thumbsDownSharp : thumbsDownOutline} style={{ fontSize: 12 }} />
      </button>
    </div>
  );
};

const StreamingIndicator: React.FC = () => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0' }}>
    <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
      {[0, 1, 2].map((index) => (
        <div
          key={index}
          style={{
            width: 7,
            height: 7,
            borderRadius: '50%',
            background: 'var(--ion-color-primary)',
            animation: `pulse 1.2s ease-in-out ${index * 0.2}s infinite`,
          }}
        />
      ))}
    </div>
    <span style={{ fontSize: 12, color: 'var(--chat-muted)', fontStyle: 'italic', animation: 'fadeIn 0.3s ease' }}>
      Generating answer...
    </span>
  </div>
);

function summaryToCopyText(summary: AssistantInterpretationSummary): string {
  const lines = [`${summary.title}: ${summary.whatItMeans}`];
  if (summary.impact) lines.push(`Impact: ${summary.impact}`);
  if (summary.canTrustIt) lines.push(`Can I trust it: ${summary.canTrustIt}`);
  if (summary.nextStep) lines.push(`Next step: ${summary.nextStep}`);
  return lines.join('\n');
}

function decodeFailureToCopyText(summary: ReturnType<typeof getDecodeFailureSummary>): string {
  if (!summary) return '';
  return [
    `${summary.title}: ${summary.whatHappened}`,
    `Can I trust it: ${summary.canTrustIt}`,
    `Next step: ${summary.nextSteps.join(' ')}`,
  ].join('\n');
}

interface UserMessageProps {
  text: string;
  inputClass: InputClass;
  timestamp?: Date;
  decodeResult?: DecodeResult | JavaDecodeResult | null;
  siconiaAnalysis?: SiconiaAnalysis | JavaSiconiaResult | null;
}

export const UserMessage: React.FC<UserMessageProps> = ({
  text,
  inputClass,
  timestamp,
  decodeResult,
  siconiaAnalysis,
}) => {
  const badge = BADGE[inputClass] ?? BADGE.query;
  const isMono = inputClass === 'hex_frame' || inputClass === 'xml_trace';

  let apduBadge: string | null = null;
  if (decodeResult) {
    if (isJavaDecodeResult(decodeResult)) {
      apduBadge = getDecodeBadge(decodeResult);
    } else if (decodeResult.apdu?.apdu_type) {
      apduBadge = decodeResult.apdu.apdu_type;
    }
  }

  let siconiaBadge: { label: string; colors: { bg: string; color: string; border: string } } | null = null;
  let layerBadge: string | null = null;
  if (siconiaAnalysis) {
    if (isJavaSiconiaResult(siconiaAnalysis)) {
      if (siconiaAnalysis.alarmResults && siconiaAnalysis.alarmResults.length > 0) {
        const severity = siconiaAnalysis.alarmResults[0].severity;
        siconiaBadge = { label: severity, colors: SEVERITY_BADGE_COLORS[severity] || SEVERITY_BADGE_COLORS.MEDIUM };
      }
      if (siconiaAnalysis.logAnalysis) {
        layerBadge = siconiaAnalysis.logAnalysis.dominantLayer;
      }
    } else {
      if (siconiaAnalysis.alarms_decoded && siconiaAnalysis.alarms_decoded.length > 0) {
        const severity = siconiaAnalysis.alarms_decoded[0].severity;
        siconiaBadge = { label: severity, colors: SEVERITY_BADGE_COLORS[severity.toUpperCase()] || SEVERITY_BADGE_COLORS.MEDIUM };
      }
      if (siconiaAnalysis.log_summary?.layers) {
        const maxLayer = Object.entries(siconiaAnalysis.log_summary.layers).reduce((a, b) => (a[1] > b[1] ? a : b))[0];
        layerBadge = maxLayer;
      }
    }
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 14, padding: '0 16px', animation: 'fadeIn 0.25s ease' }}>
      <div style={{ width: 'min(100%, 720px)', display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 6, gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
          {timestamp && (
            <span style={{ fontSize: 10, color: 'var(--chat-muted-2)', fontWeight: 500 }}>
              {formatTime(timestamp)}
            </span>
          )}
          <span style={{
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.05em',
            padding: '3px 10px',
            borderRadius: 999,
            background: badge.bg,
            color: badge.color,
            border: `1px solid ${badge.border}`,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
          }}>
            <span style={{ fontSize: 9, opacity: 0.8 }}>{badge.icon}</span>
            {badge.label}
          </span>
          {apduBadge && (
            <span style={{
              fontSize: 9,
              fontWeight: 700,
              letterSpacing: '0.03em',
              padding: '3px 8px',
              borderRadius: 999,
              background: 'var(--chat-primary-soft)',
              color: 'var(--ion-color-primary)',
              border: '1px solid var(--chat-border)',
            }}>
              {apduBadge}
            </span>
          )}
          {siconiaBadge && (
            <span style={{
              fontSize: 9,
              fontWeight: 700,
              letterSpacing: '0.03em',
              padding: '3px 8px',
              borderRadius: 999,
              background: siconiaBadge.colors.bg,
              color: siconiaBadge.colors.color,
              border: `1px solid ${siconiaBadge.colors.border}`,
            }}>
              {siconiaBadge.label}
            </span>
          )}
          {layerBadge && (
            <span style={{
              fontSize: 9,
              fontWeight: 700,
              letterSpacing: '0.03em',
              padding: '3px 8px',
              borderRadius: 999,
              background: LAYER_BADGE_COLORS[layerBadge]?.bg || 'var(--chat-surface)',
              color: LAYER_BADGE_COLORS[layerBadge]?.color || 'var(--chat-muted)',
              border: `1px solid ${LAYER_BADGE_COLORS[layerBadge]?.border || 'var(--chat-border)'}`,
            }}>
              {layerBadge}
            </span>
          )}
        </div>
        <div style={{
          background: 'var(--chat-user-bubble)',
          color: 'var(--chat-user-text)',
          borderRadius: '18px 18px 4px 18px',
          padding: '12px 18px',
          fontSize: 14,
          lineHeight: 1.7,
          fontFamily: isMono ? '"Fira Code", "Consolas", "Cascadia Code", monospace' : 'system-ui, -apple-system, sans-serif',
          wordBreak: 'break-word',
          whiteSpace: 'pre-wrap',
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          maxWidth: 'min(86vw, 680px)',
        }}>
          {text}
        </div>
      </div>
    </div>
  );
};

interface AssistantMessageProps {
  text: string;
  isStreaming?: boolean;
  decodeResult?: DecodeResult | JavaDecodeResult | null;
  siconiaAnalysis?: SiconiaAnalysis | JavaSiconiaResult | null;
  artifactResults?: JavaArtifactResult[] | null;
  strategyMetadata?: JavaStrategyMetadata | null;
  usedFallback?: boolean;
  explanationMode?: JavaExplanationMode | null;
  toolProvenance?: JavaToolProvenance | null;
  orchestrationMode?: JavaOrchestrationMode | null;
  plannerUsed?: boolean | null;
  toolTrace?: JavaToolTraceEntry[] | null;
  plannerFallbackReason?: string | null;
  timestamp?: Date;
  uiCategory?: UiConversationCategory;
  onFeedback?: (value: FeedbackValue) => void;
}

export const AssistantMessage: React.FC<AssistantMessageProps> = ({
  text,
  isStreaming,
  decodeResult,
  siconiaAnalysis,
  artifactResults,
  strategyMetadata,
  usedFallback,
  explanationMode,
  toolProvenance,
  orchestrationMode,
  plannerUsed,
  toolTrace,
  plannerFallbackReason,
  timestamp,
  uiCategory = 'general',
  onFeedback,
}) => {
  const [decodeOpen, setDecodeOpen] = useState(false);
  const [traceOpen, setTraceOpen] = useState(false);

  const javaDecode = decodeResult && isJavaDecodeResult(decodeResult) ? decodeResult : null;
  const javaSiconia = siconiaAnalysis && isJavaSiconiaResult(siconiaAnalysis) ? siconiaAnalysis : null;
  const legacyDecode = decodeResult && !javaDecode ? decodeResult as DecodeResult : null;
  const legacySiconia = siconiaAnalysis && !javaSiconia ? siconiaAnalysis as SiconiaAnalysis : null;
  const artifactSections = artifactResults && artifactResults.length > 0 ? artifactResults : null;
  const hasStructured = !!(javaDecode || javaSiconia || legacyDecode || legacySiconia);
  const siconiaProvenance = javaSiconia?.processingMetadata?.provenance;
  const dlmsProvenancePresentation = getDlmsProvenancePresentation(javaDecode);
  const normalizedText = formatStructuredNarrationForMarkdown(
    normalizeTechnicalReply(compactGreetingReply(text)),
  );
  const hasVisibleBackendText = normalizedText.trim().length > 0;
  const backendStructuredNarrative = hasStructured && (
    hasVisibleBackendText
    || explanationMode !== null && explanationMode !== undefined
    || Boolean(usedFallback)
  );
  const showCandidateCard = Boolean(strategyMetadata?.ambiguous && strategyMetadata.candidates.length > 0);

  const stageLabel = javaDecode
    ? normalizeVisibleText(getDecodeBadge(javaDecode))
    : javaSiconia
      ? normalizeVisibleText(`SICONIA - ${javaSiconia.inputClass || 'analysis'}`)
      : legacyDecode && typeof legacyDecode.stage === 'string'
        ? normalizeVisibleText(`Decode result - ${legacyDecode.stage.replace('_', ' ').toUpperCase()}`)
        : legacySiconia
          ? normalizeVisibleText(`SICONIA - ${(legacySiconia.input_type ?? 'analysis').replace('_', ' ')}`)
          : null;

  const decodeFailureSummary = getDecodeFailureSummary(javaDecode);
  const showDecodeFailureSummary = Boolean(decodeFailureSummary && !backendStructuredNarrative);
  const interpretationSummary = showDecodeFailureSummary || backendStructuredNarrative
    ? null
    : getSiconiaInterpretationSummary(javaSiconia) || getDecodeInterpretationSummary(javaDecode);
  const displayText = showDecodeFailureSummary
    ? ''
    : interpretationSummary
      ? stripRedundantStructuredExplanation(normalizedText)
      : normalizedText.trim();
  const traceSignalsPresent = Boolean(
    orchestrationMode
    || typeof plannerUsed === 'boolean'
    || (toolTrace && toolTrace.length > 0)
    || plannerFallbackReason
    || strategyMetadata
    || explanationMode
    || toolProvenance
  );
  const orderedToolTrace = toolTrace && toolTrace.length > 0 ? toolTrace : null;
  const trustNote = trustSummary(explanationMode, toolProvenance);
  const normalizedFallbackReason = plannerFallbackReason ? normalizeVisibleText(plannerFallbackReason) : null;
  const traceModeLabel = orchestrationMode ? ORCHESTRATION_MODE_LABELS[orchestrationMode] : null;
  const strategyTraceLabel = strategyMetadata ? normalizeVisibleText(strategyMetadata.selectedLabel) : null;
  const strategyTraceConfidence = strategyMetadata ? `${Math.round(strategyMetadata.confidence * 100)}%` : null;

  const copyText = [
    showDecodeFailureSummary && decodeFailureSummary ? decodeFailureToCopyText(decodeFailureSummary) : '',
    interpretationSummary ? summaryToCopyText(interpretationSummary) : '',
    displayText,
    ...(artifactSections?.map((artifact) => {
      const artifactText = normalizeTechnicalReply(compactGreetingReply(artifact.explanation || ''));
      return artifactText.trim()
        ? `Artifact ${artifact.index + 1}\n${artifactText}`
        : '';
    }) || []),
  ].filter(Boolean).join('\n\n');

  return (
    <div style={{ display: 'flex', gap: 12, marginBottom: 24, padding: '0 16px', alignItems: 'flex-start', animation: 'fadeIn 0.25s ease' }}>
      <div style={{
        width: 32,
        height: 32,
        borderRadius: 10,
        flexShrink: 0,
        marginTop: 2,
        background: 'linear-gradient(135deg, var(--chat-gradient-start), var(--chat-gradient-end))',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        boxShadow: '0 2px 8px rgba(90,90,245,0.25)',
        fontSize: 12,
        fontWeight: 800,
        color: '#fff',
      }}>
        AI
      </div>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--chat-muted)', letterSpacing: '-0.02em' }}>
            DLMS Assistant
          </span>
          {timestamp && (
            <span style={{ fontSize: 10, color: 'var(--chat-muted-2)', fontWeight: 500 }}>
              {formatTime(timestamp)}
            </span>
          )}
          {usedFallback && (
            <span style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              padding: '4px 8px',
              borderRadius: 999,
              border: '1px solid rgba(217,119,6,0.16)',
              background: 'rgba(217,119,6,0.08)',
              color: 'var(--ion-color-warning)',
              fontSize: 10.5,
              fontWeight: 700,
            }}>
              <IonIcon icon={warningOutline} style={{ fontSize: 11 }} />
              Deterministic fallback
            </span>
          )}
          {explanationMode && (
            <span
              data-testid="assistant-explanation-mode"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 8px',
                borderRadius: 999,
                fontSize: 10.5,
                fontWeight: 700,
                ...EXPLANATION_MODE_STYLES[explanationMode],
              }}
            >
              {EXPLANATION_MODE_LABELS[explanationMode]}
            </span>
          )}
          {toolProvenance && (
            <span
              data-testid="assistant-tool-provenance"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 8px',
                borderRadius: 999,
                fontSize: 10.5,
                fontWeight: 700,
                ...TOOL_PROVENANCE_STYLES[toolProvenance],
              }}
            >
              {TOOL_PROVENANCE_LABELS[toolProvenance]}
            </span>
          )}
          {uiCategory !== 'general' && (
            <span
              data-testid="assistant-category-badge"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 8px',
                borderRadius: 999,
                border: '1px solid var(--chat-border)',
                background: 'var(--chat-surface)',
                color: uiCategory === 'security' ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                fontSize: 10.5,
                fontWeight: 700,
                textTransform: 'capitalize',
              }}
            >
              <IonIcon icon={CATEGORY_ICONS[uiCategory]} style={{ fontSize: 11 }} />
              {uiCategory}
            </span>
          )}
          {siconiaProvenance && (
            <span
              data-testid="assistant-siconia-provenance"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 8px',
                borderRadius: 999,
                fontSize: 10.5,
                fontWeight: 700,
                ...SICONIA_PROVENANCE_COLORS[siconiaProvenance],
              }}
            >
              {SICONIA_PROVENANCE_LABELS[siconiaProvenance]}
            </span>
          )}
          {dlmsProvenancePresentation && (
            <span
              data-testid="assistant-dlms-provenance"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 8px',
                borderRadius: 999,
                fontSize: 10.5,
                fontWeight: 700,
                ...dlmsProvenancePresentation.style,
              }}
            >
              {dlmsProvenancePresentation.label}
            </span>
          )}
          <div style={{ flex: 1 }} />
          {!isStreaming && onFeedback && <FeedbackButton onFeedback={onFeedback} />}
          {!isStreaming && copyText && <CopyButton text={copyText} />}
        </div>

        <div style={{
          background: 'var(--chat-assistant-bg)',
          border: '1px solid var(--chat-border)',
          borderRadius: 20,
          padding: '14px 16px 16px',
          boxShadow: 'var(--chat-shadow)',
        }}>
          {!artifactSections && hasStructured && stageLabel && (
            <button
              type="button"
              onClick={() => setDecodeOpen((open) => !open)}
              aria-expanded={decodeOpen}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                marginBottom: 12,
                padding: '7px 12px 7px 10px',
                background: decodeOpen ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
                border: '1px solid var(--chat-border)',
                borderRadius: 999,
                cursor: 'pointer',
                color: decodeOpen ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                fontSize: 12,
                fontFamily: 'inherit',
                fontWeight: 700,
                transition: 'all 0.15s',
              }}
            >
              <IonIcon icon={decodeOpen ? chevronDownOutline : chevronForwardOutline} style={{ fontSize: 13 }} />
              {stageLabel}
              {usedFallback && (
                <IonIcon icon={warningOutline} style={{ color: 'var(--ion-color-warning)', fontSize: 12, marginLeft: 2 }} />
              )}
            </button>
          )}

          {!artifactSections && decodeOpen && (
            <div style={{ marginBottom: 16, animation: 'slideUp 0.2s ease' }}>
              {javaDecode && <DecodeTreePanel result={javaDecode} />}
              {javaDecode?.profileResult && <div style={{ marginTop: 8 }}><ProfileResultPanel result={javaDecode.profileResult} /></div>}
              {javaSiconia && <div style={{ marginTop: 8 }}><SiconiaResultPanel result={javaSiconia} /></div>}
              {legacyDecode && <DecodeTree result={legacyDecode} usedFallback={usedFallback ?? false} />}
            </div>
          )}

          {!artifactSections && usedFallback && !hasStructured && (
            <div style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              marginBottom: 12,
              padding: '8px 12px',
              background: 'rgba(217,119,6,0.08)',
              border: '1px solid rgba(217,119,6,0.2)',
              borderRadius: 12,
              animation: 'fadeIn 0.2s ease',
            }}>
              <IonIcon icon={warningOutline} style={{ color: 'var(--ion-color-warning)', fontSize: 13 }} />
              <span style={{ fontSize: 12, color: 'var(--ion-color-warning)' }}>
                Deterministic fallback summary in use for this answer
              </span>
            </div>
          )}

          {!artifactSections && showDecodeFailureSummary && decodeFailureSummary && (
            <div
              style={{
                marginBottom: 12,
                padding: '10px 12px',
                background: 'rgba(217,119,6,0.08)',
                border: '1px solid rgba(217,119,6,0.18)',
                borderRadius: 12,
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  marginBottom: 4,
                  color: 'var(--ion-color-warning)',
                  fontSize: 12,
                  fontWeight: 700,
                }}
              >
                <IonIcon icon={warningOutline} style={{ fontSize: 13 }} />
                {decodeFailureSummary.title}
              </div>
              <div style={{ display: 'grid', gap: 8, fontSize: 12.5, lineHeight: 1.6, color: 'var(--chat-muted)' }}>
                <div>
                  <strong style={{ color: 'var(--chat-text)' }}>What happened:</strong>{' '}
                  {decodeFailureSummary.whatHappened}
                </div>
                <div>
                  <strong style={{ color: 'var(--chat-text)' }}>Can I trust it:</strong>{' '}
                  {decodeFailureSummary.canTrustIt}
                </div>
                <div>
                  <strong style={{ color: 'var(--chat-text)' }}>What to do next:</strong>{' '}
                  {decodeFailureSummary.nextSteps.join(' ')}
                </div>
              </div>
            </div>
          )}
          {!artifactSections && showCandidateCard && strategyMetadata && (
            <div
              style={{
                marginBottom: 12,
                padding: '10px 12px',
                background: 'var(--chat-surface)',
                border: '1px solid var(--chat-border)',
                borderRadius: 12,
              }}
            >
              <div
                style={{
                  marginBottom: 8,
                  color: 'var(--chat-text)',
                  fontSize: 12,
                  fontWeight: 700,
                }}
              >
                Possible interpretations
              </div>
              <div style={{ display: 'grid', gap: 8 }}>
                {strategyMetadata.candidates.slice(0, 3).map((candidate, index) => (
                  <div
                    key={`${candidate.strategy}-${index}`}
                    style={{
                      padding: '8px 10px',
                      borderRadius: 10,
                      border: '1px solid var(--chat-border)',
                      background: index === 0 ? 'var(--chat-primary-soft)' : 'var(--chat-assistant-bg)',
                    }}
                  >
                    {(() => {
                      const candidateLabel = normalizeVisibleText(candidate.label);
                      const candidateRationale = candidate.rationale
                        ? normalizeVisibleText(candidate.rationale)
                        : null;
                      return (
                        <>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4, flexWrap: 'wrap' }}>
                      <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--chat-text)' }}>
                        {index === 0 ? `Recommended: ${candidateLabel}` : candidateLabel}
                      </span>
                      <span style={{ fontSize: 11, color: 'var(--chat-muted)' }}>
                        {Math.round(candidate.confidence * 100)}%
                      </span>
                    </div>
                    {candidateRationale && (
                      <div style={{ fontSize: 12, lineHeight: 1.55, color: 'var(--chat-muted)' }}>
                        {candidateRationale}
                      </div>
                    )}
                        </>
                      );
                    })()}
                  </div>
                ))}
              </div>
            </div>
          )}

          {!artifactSections && interpretationSummary && (
            <div
              style={{
                marginBottom: 12,
                padding: '10px 12px',
                background: 'var(--chat-surface)',
                border: '1px solid var(--chat-border)',
                borderRadius: 12,
              }}
            >
              <div
                style={{
                  marginBottom: 6,
                  color: 'var(--chat-text)',
                  fontSize: 12,
                  fontWeight: 700,
                }}
              >
                {normalizeVisibleText(interpretationSummary.title)}
              </div>
              <div style={{ display: 'grid', gap: 8, fontSize: 12.5, lineHeight: 1.6, color: 'var(--chat-muted)' }}>
                <div>
                  <strong style={{ color: 'var(--chat-text)' }}>What it means:</strong>{' '}
                  {normalizeVisibleText(interpretationSummary.whatItMeans)}
                </div>
                {interpretationSummary.impact && (
                  <div>
                    <strong style={{ color: 'var(--chat-text)' }}>Impact:</strong>{' '}
                    {normalizeVisibleText(interpretationSummary.impact)}
                  </div>
                )}
                {interpretationSummary.canTrustIt && (
                  <div>
                    <strong style={{ color: 'var(--chat-text)' }}>Can I trust it:</strong>{' '}
                    {normalizeVisibleText(interpretationSummary.canTrustIt)}
                  </div>
                )}
                {interpretationSummary.nextStep && (
                  <div>
                    <strong style={{ color: 'var(--chat-text)' }}>Next step:</strong>{' '}
                    {normalizeVisibleText(interpretationSummary.nextStep)}
                  </div>
                )}
              </div>
            </div>
          )}

          {(displayText || isStreaming) && (
            <div style={{
              fontSize: 14.5,
              lineHeight: 1.82,
              color: 'var(--chat-text)',
              fontFamily: 'system-ui, -apple-system, sans-serif',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}>
              {!displayText && isStreaming ? (
                <StreamingIndicator />
              ) : isStreaming ? (
                <span>{displayText}</span>
              ) : (
                <RichText text={displayText} />
              )}
              {isStreaming && displayText && (
                <span style={{
                  display: 'inline-block',
                  width: 2,
                  height: '1.1em',
                  background: 'var(--ion-color-primary)',
                  marginLeft: 2,
                  verticalAlign: 'text-bottom',
                  animation: 'blink 1s step-end infinite',
                }} />
              )}
            </div>
          )}

          {artifactSections && artifactSections.map((artifact) => (
            <ArtifactSection
              key={artifact.artifactId || `${artifact.index}-${artifact.filename || 'artifact'}`}
              artifact={artifact}
            />
          ))}

          {traceSignalsPresent && (
            <div
              data-testid="assistant-trace-container"
              style={{
                marginTop: 12,
                paddingTop: 12,
                borderTop: '1px solid var(--chat-border)',
              }}
            >
              <button
                type="button"
                data-testid="assistant-trace-toggle"
                onClick={() => setTraceOpen((open) => !open)}
                aria-expanded={traceOpen}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '6px 10px',
                  borderRadius: 10,
                  border: '1px solid var(--chat-border)',
                  background: traceOpen ? 'var(--chat-primary-soft)' : 'var(--chat-surface)',
                  color: traceOpen ? 'var(--ion-color-primary)' : 'var(--chat-muted)',
                  cursor: 'pointer',
                  fontSize: 12,
                  fontWeight: 700,
                  fontFamily: 'inherit',
                }}
              >
                <IonIcon icon={traceOpen ? chevronDownOutline : chevronForwardOutline} style={{ fontSize: 13 }} />
                How I answered
              </button>

              {traceOpen && (
                <div
                  data-testid="assistant-trace-panel"
                  style={{
                    marginTop: 10,
                    padding: '10px 12px',
                    borderRadius: 12,
                    background: 'var(--chat-surface)',
                    border: '1px solid var(--chat-border)',
                    display: 'grid',
                    gap: 8,
                    fontSize: 12.5,
                    lineHeight: 1.6,
                    color: 'var(--chat-muted)',
                  }}
                >
                  {traceModeLabel && (
                    <div>
                      <strong style={{ color: 'var(--chat-text)' }}>Mode:</strong>{' '}
                      {traceModeLabel}
                    </div>
                  )}
                  {strategyTraceLabel && (
                    <div>
                      <strong style={{ color: 'var(--chat-text)' }}>Strategy:</strong>{' '}
                      {strategyTraceLabel}
                      {strategyTraceConfidence ? ` (${strategyTraceConfidence})` : ''}
                      {strategyMetadata?.tentative ? ' · tentative' : ''}
                      {strategyMetadata?.ambiguous ? ' · ambiguous' : ''}
                    </div>
                  )}
                  {typeof plannerUsed === 'boolean' && (
                    <div>
                      <strong style={{ color: 'var(--chat-text)' }}>Planner:</strong>{' '}
                      {plannerUsed ? 'Yes' : 'No'}
                    </div>
                  )}
                  {orderedToolTrace && (
                    <div>
                      <strong style={{ color: 'var(--chat-text)' }}>Tools used:</strong>
                      <div style={{ display: 'grid', gap: 8, marginTop: 6 }}>
                        {orderedToolTrace.map((entry, index) => (
                          <div
                            key={`${entry.toolName}-${index}`}
                            style={{
                              padding: '8px 10px',
                              borderRadius: 10,
                              border: '1px solid var(--chat-border)',
                              background: 'var(--chat-assistant-bg)',
                            }}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginBottom: entry.summary ? 4 : 0 }}>
                              <span style={{ color: 'var(--chat-text)', fontWeight: 700 }}>
                                {normalizeVisibleText(entry.toolName)}
                              </span>
                              {entry.authoritative && (
                                <span style={{
                                  display: 'inline-flex',
                                  alignItems: 'center',
                                  padding: '2px 6px',
                                  borderRadius: 999,
                                  border: '1px solid rgba(34,197,94,0.2)',
                                  background: 'rgba(34,197,94,0.1)',
                                  color: 'var(--ion-color-success)',
                                  fontSize: 10.5,
                                  fontWeight: 700,
                                }}>
                                  Authoritative
                                </span>
                              )}
                              {entry.provenance && (
                                <span style={{ fontSize: 11, color: 'var(--chat-muted-2)' }}>
                                  {normalizeVisibleText(entry.provenance)}
                                </span>
                              )}
                            </div>
                            {entry.summary && (
                              <div>{normalizeVisibleText(entry.summary)}</div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                  {normalizedFallbackReason && (
                    <div>
                      <strong style={{ color: 'var(--chat-text)' }}>Fallback:</strong>{' '}
                      {normalizedFallbackReason}
                    </div>
                  )}
                  {trustNote && (
                    <div>
                      <strong style={{ color: 'var(--chat-text)' }}>Trust:</strong>{' '}
                      {normalizeVisibleText(trustNote)}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
