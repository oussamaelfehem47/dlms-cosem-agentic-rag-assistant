// ── Input classification ──────────────────────────────────────────────────────

export type InputClass =
  | 'hex_frame'
  | 'xml_trace'
  | 'alarm_code'
  | 'log_block'
  | 'query'
  | 'unknown';

// ── Decode result tree (legacy Python decoder format) ─────────────────────────

export interface HdlcResult {
  frame_type?: string;
  frame_length?: number;
  dest_address?: { raw: string; value: number; bytes: number };
  src_address?: { raw: string; value: number; bytes: number };
  control?: {
    raw: string;
    type: string;
    decoded: {
      unnumbered_type?: string;
      supervisory_type?: string;
      [key: string]: unknown;
    };
  };
  hcs_valid: boolean;
  fcs_valid: boolean;
  information_hex?: string;
  information_length?: number;
  errors: string[];
  warnings: string[];
}

export interface ApduResult {
  llc?: { dst_lsap: string; src_lsap: string; is_dlms: boolean };
  apdu_type: string;
  apdu_tag?: string;
  description?: string;
  sub_type?: string;
  payload_hex: string;
  payload_length?: number;
  warnings: string[];
}

export interface AxdrResult {
  type: string;
  tag: string;
  value?: unknown;
  children?: AxdrResult[];
}

export interface ObisResult {
  obis: string;
  resolved: boolean;
  description?: string;
  interface_class?: { id: number; name: string } | null;
  structural_decode?: {
    medium: string;
    channel: string;
    quantity: string;
    measurement_type: string;
    billing_period: string;
  };
}

export type DecodeStage = 'hdlc' | 'apdu' | 'axdr' | 'obis' | 'hdlc_error' | 'hdlc_supervisory' | 'fallback';

export interface DecodeResult {
  stage: DecodeStage;
  hdlc?: HdlcResult;
  apdu?: ApduResult;
  axdr?: AxdrResult;
  obis?: ObisResult;
}

// ── Java backend DecodeResult format (from SSE decode event) ──────────────────

export interface JavaHdlcFrame {
  frameType: string;       // "I_FRAME" | "S_FRAME" | "U_FRAME"
  uFrameType?: string;     // "SNRM" | "UA" | "DM" | "DISC"
  sFrameType?: string;     // "RR" | "RNR" | "REJ"
  clientSap: number;
  serverSap: number;
  informationField?: string; // Base64 encoded
  fcsValid: boolean;
  rawBytes?: string;         // Base64 encoded
}

export interface JavaObisResolution {
  obis: string;
  description: string;
  ic: number;
  icName?: string;         // Interface class name e.g. "Register", "ProfileGeneric"
  unit: string;
  scaler: number;
  tierUsed: string;       // "KG" | "STRUCTURAL" | "RAG"
}

export interface JavaAxdrValue {
  type: string;            // "null" | "array" | "structure" | "boolean" | "int32" | ...
  tag: string;             // hex tag like "0x01"
  value?: unknown;
  children?: JavaAxdrValue[];
}

export interface JavaProfileCell {
  columnIndex: number;
  rawValue?: unknown;
  scaledValue?: unknown;
  displayString: string;
  unit?: string | null;
}

export interface JavaProfileRow {
  timestamp?: string | null;
  cells: JavaProfileCell[];
}

export interface JavaProfileColumn {
  index: number;
  obis: string;
  description: string;
  classId: number;
  attributeIndex: number;
  unit?: string | null;
  scaler?: number | null;
}

export interface JavaProfileResult {
  profileType: string;
  columns: JavaProfileColumn[];
  rows: JavaProfileRow[];
  captureObjectCount: number;
  entryCount: number;
  obis?: string | null;
}

export type JavaDlmsProvenance =
  | 'STRUCTURED_DIRECT'
  | 'STRUCTURED_HEURISTIC'
  | 'RAW_FALLBACK';

export type JavaExplanationMode =
  | 'DETERMINISTIC_ONLY'
  | 'GROUNDED_LLM'
  | 'TENTATIVE_GROUNDED';

export type JavaToolProvenance =
  | 'MCP'
  | 'JAVA'
  | 'MIXED';

export type JavaOrchestrationMode =
  | 'DETERMINISTIC_FAST_PATH'
  | 'STRUCTURED_PLUS_AGENTIC'
  | 'NATURAL_LANGUAGE_AGENTIC'
  | 'AMBIGUOUS_SAFE_FALLBACK';

export interface JavaToolTraceEntry {
  toolName: string;
  summary: string;
  authoritative: boolean;
  provenance: string;
}

export type JavaArtifactSource = 'ATTACHMENT' | 'PASTED_BLOCK';

export interface WorkflowArtifactInput {
  artifactId?: string;
  source: JavaArtifactSource;
  filename?: string | null;
  text: string;
  hintedInputClass?: InputClass | null;
  suggestedEndpoint?: 'decode' | 'siconia' | null;
}

export type JavaDlmsNormalizedKind =
  | 'FRAME_HEX'
  | 'APDU_HEX'
  | 'AXDR_HEX'
  | 'OBIS_QUERY';

export interface JavaDlmsProcessingMetadata {
  normalizedKind: JavaDlmsNormalizedKind;
  provenance: JavaDlmsProvenance;
  warnings: string[];
  extractorNote?: string | null;
}

export interface JavaStrategyCandidate {
  strategy: string;
  label: string;
  confidence: number;
  rationale?: string | null;
  deterministic: boolean;
  tentative: boolean;
  inputClass?: string | null;
  normalizedKind?: string | null;
  provenance?: string | null;
  normalizedInput?: string | null;
  warnings: string[];
}

export interface JavaStrategyMetadata {
  selectedStrategy: string;
  selectedLabel: string;
  confidence: number;
  ambiguous: boolean;
  tentative: boolean;
  candidates: JavaStrategyCandidate[];
  warnings: string[];
}

export interface JavaDecodeResult {
  hdlcFrame?: JavaHdlcFrame;
  apduType?: string;       // "GET_RESPONSE" | "GET_REQUEST" | ...
  apduTag?: string;        // hex APDU tag like "0xC4"
  frameLength?: number;    // total frame length in bytes
  axdrTree?: JavaAxdrValue;
  obisResolutions?: JavaObisResolution[];
  profileResult?: JavaProfileResult;
  processingMetadata?: JavaDlmsProcessingMetadata | null;
  gbtPartial: boolean;
  rawHex?: string;
  parseErrors?: string[];
  anomalies?: string[];
}

export interface JavaArtifactResult {
  artifactId: string;
  index: number;
  source: JavaArtifactSource;
  filename?: string | null;
  rawInput: string;
  inputClass?: string | null;
  intent?: string | null;
  decodeResult?: JavaDecodeResult | null;
  siconiaResult?: JavaSiconiaResult | null;
  explanation: string;
  strategyMetadata?: JavaStrategyMetadata | null;
  orchestrationMode?: JavaOrchestrationMode | null;
  plannerUsed?: boolean | null;
  toolTrace?: JavaToolTraceEntry[] | null;
  plannerFallbackReason?: string | null;
  explanationMode?: JavaExplanationMode | null;
  toolProvenance?: JavaToolProvenance | null;
}

// ── Java backend SICONIA result format (from SSE analysis event) ──────────────

export interface JavaAlarmDecodeResult {
  code: string;
  severity: string;        // "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO"
  rootCause: string;
  remediation: string;
  affectedComponent: string; // "WAN" | "PLC" | "RF" | "HES" | "METER" | "SECURITY"
}

export interface JavaLogAnalysis {
  dominantLayer: string;   // "WAN" | "PLC" | "RF" | "HES" | "DLMS"
  highestSeverity: string; // "ERROR" | "WARN" | "INFO" | "DEBUG"
  issueCategories: string[]; // ["CONNECTIVITY", "SECURITY", ...]
  lineCount: number;
  errorLineCount: number;
}

export type JavaSiconiaProvenance =
  | 'STRUCTURED_DIRECT'
  | 'STRUCTURED_HEURISTIC'
  | 'RAW_FALLBACK';

export interface JavaSiconiaProcessingMetadata {
  normalizedInputClass: string;
  provenance: JavaSiconiaProvenance;
  warnings: string[];
  extractorNote?: string | null;
}

export interface JavaXmlTraceEvent {
  type?: string;
  code?: string;
  timestamp?: string;
  deviceId?: string;
  errorCode?: string;
  [key: string]: unknown;
}

export interface JavaXmlTrace {
  events?: JavaXmlTraceEvent[];
  event?: JavaXmlTraceEvent;
  parseErrors?: string[];
  rawXml?: string;
  [key: string]: unknown;
}

export interface JavaSiconiaResult {
  xmlTrace?: JavaXmlTrace | null;
  alarmResults?: JavaAlarmDecodeResult[];
  logAnalysis?: JavaLogAnalysis;
  processingMetadata?: JavaSiconiaProcessingMetadata | null;
  inputClass: string;
}

export type StructuredMessagePayload =
  | DecodeResult
  | JavaDecodeResult
  | SiconiaAnalysis
  | JavaSiconiaResult;

// ── SICONIA (legacy format) ──────────────────────────────────────────────────

export interface AlarmDecoded {
  code_hex: string;
  resolved: boolean;
  category?: string;
  severity: string;
  description?: string;
  root_causes?: string[];
  remediation?: string[];
}

export interface SiconiaAnalysis {
  input_type: string;
  sessions?: number;
  total_events?: number;
  alarm_count?: number;
  alarms?: Array<{ code: string; severity: string; ts?: string }>;
  alarms_decoded?: AlarmDecoded[];
  layers_seen?: string[];
  meters_seen?: string[];
  log_summary?: {
    total_lines: number;
    layers: Record<string, number>;
    severities: Record<string, number>;
    alarm_codes_found: string[];
  };
}

// ── SSE stream events ─────────────────────────────────────────────────────────

export type StreamEvent =
  | { type: 'decode'; payload: DecodeResult | null; session_id: string; used_fallback: boolean }
  | { type: 'analysis'; payload: SiconiaAnalysis; input_type: string; session_id: string; used_fallback: boolean }
  | { type: 'token'; token: string }
  | { type: 'filtered'; blocked: boolean }
  | { type: 'done'; session_id: string };

// ── Conversation history ──────────────────────────────────────────────────────

export interface ConversationEntry {
  id: string;
  timestamp: Date;
  inputClass: InputClass;
  userInput: string;
  decodeResult: DecodeResult | JavaDecodeResult | null;
  siconiaAnalysis: SiconiaAnalysis | JavaSiconiaResult | null;
  artifactResults?: JavaArtifactResult[] | null;
  explanation: string;
  sessionId: string;
  usedFallback: boolean;
  explanationMode?: JavaExplanationMode | null;
  toolProvenance?: JavaToolProvenance | null;
  orchestrationMode?: JavaOrchestrationMode | null;
  plannerUsed?: boolean | null;
  toolTrace?: JavaToolTraceEntry[] | null;
  plannerFallbackReason?: string | null;
  intent?: string;
  strategyMetadata?: JavaStrategyMetadata | null;
}

// ── MCP status ────────────────────────────────────────────────────────────────

export interface McpStatus {
  reachable: boolean;
  lastChecked: Date | null;
  checking: boolean;
}

// ── Theme ─────────────────────────────────────────────────────────────────────

export type ThemeMode = 'light' | 'dark' | 'system';

// ── Tool Testing ──────────────────────────────────────────────────────────────

export type McpToolCategory = 'dlms' | 'siconia' | 'confluence';

export interface McpToolDef {
  name: string;
  category: McpToolCategory;
  description: string;
  sampleInput: string;
  sampleInputLabel: string;
  inputPlaceholder: string;
}

export interface ToolTestResult {
  toolName: string;
  success: boolean;
  durationMs: number;
  result: unknown;
  error?: string;
}

export interface CurrentUserProfile {
  user_id: string;
  username: string;
  email: string;
  role: string;
  created_at: string;
  active: boolean;
}

export interface ReflectionStatsResponse {
  totalExecutions: number;
  feedbackDatasetSize: number;
  dislikedResponseCount: number;
  intentDistribution: Record<string, number>;
  mcpFailureRate?: number;
  emptyRetrievalRate?: Record<string, number>;
  filterTriggerRate?: number;
  parseErrorRate?: number;
  avgResponseTimeMs?: Record<string, number>;
  warnings?: string[];
  lastUpdated?: string;
  activeAdaptations?: Record<string, unknown>;
}

export interface AdminUser {
  userId: string;
  username: string;
  email: string;
  role: string;
  active: boolean;
  createdAt: string;
}

export interface AdminFeedback {
  id: string;
  messageId?: string;
  conversationId?: string;
  userId?: string;
  intent: string;
  inputClass: string;
  feedback: 'like' | 'dislike';
  promptSnapshot?: string;
  responseSnapshot?: string;
  modelName?: string;
  createdAt: string;
}

export interface RegisterUserRequest {
  username: string;
  email: string;
  password: string;
  role: string;
}

export interface HealthComponentStatus {
  status: string;
  details?: Record<string, unknown>;
}

export interface HealthResponse {
  status: string;
  components?: Record<string, HealthComponentStatus>;
}

export interface McpHealthResponse {
  reachable: boolean;
  toolCount?: number;
  tools?: string[];
  error?: string;
}

export type ActuatorInfoResponse = Record<string, unknown>;
