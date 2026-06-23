import {
  bugOutline,
  codeSlashOutline,
  documentTextOutline,
  flashOutline,
  informationCircleOutline,
  lockClosedOutline,
  warningOutline,
} from 'ionicons/icons';
import { InputClass } from '../types';
import { ComposerPreset, UiConversationCategory } from './chatFeatureUtils';

export interface ChatBadgeConfig {
  label: string;
  color: 'primary' | 'tertiary' | 'danger' | 'warning' | 'success' | 'medium';
  icon: string;
}

export interface SampleData {
  label: string;
  description: string;
  text: string;
  inputClass: Exclude<InputClass, 'unknown'>;
  category: UiConversationCategory;
}

export interface EmptyStateAction {
  label: string;
  description: string;
  prompt: string;
  category: UiConversationCategory;
}

export interface ComposerPresetConfig {
  id: ComposerPreset;
  label: string;
  description: string;
  starter: string;
}

export const CHAT_BADGE: Record<InputClass, ChatBadgeConfig> = {
  hex_frame: { label: 'HDLC', color: 'primary', icon: codeSlashOutline },
  xml_trace: { label: 'XML', color: 'tertiary', icon: documentTextOutline },
  alarm_code: { label: 'Alarm', color: 'danger', icon: flashOutline },
  log_block: { label: 'Log', color: 'warning', icon: bugOutline },
  query: { label: 'Query', color: 'success', icon: informationCircleOutline },
  unknown: { label: 'Unknown', color: 'medium', icon: informationCircleOutline },
};

export const EMPTY_STATE_ACTIONS: EmptyStateAction[] = [
  {
    label: 'Decode a frame',
    description: 'Paste a frame.',
    prompt: 'Explain this DLMS frame step by step and highlight anomalies:',
    category: 'decode',
  },
  {
    label: 'Troubleshoot alarms',
    description: 'Analyze alarms or logs.',
    prompt: 'Help me troubleshoot this SICONIA issue and suggest remediation:',
    category: 'incident',
  },
  {
    label: 'Explain HLS',
    description: 'Explain HLS authentication.',
    prompt: 'Explain HLS authentication in DLMS/COSEM and when it is used.',
    category: 'security',
  },
  {
    label: 'Explain replay protection',
    description: 'Explain replay protection.',
    prompt: 'Explain replay protection and the role of the frame counter in DLMS security.',
    category: 'security',
  },
];

export const COMPOSER_PRESETS: ComposerPresetConfig[] = [
  {
    id: 'security',
    label: 'Security',
    description: 'Security questions.',
    starter: 'Explain this DLMS security concept or issue: ',
  },
  {
    id: 'decode',
    label: 'Decode',
    description: 'Decode frames and protocol details.',
    starter: 'Decode and explain this DLMS/COSEM input: ',
  },
  {
    id: 'incident',
    label: 'Incident',
    description: 'Investigate alarms and failures.',
    starter: 'Analyze this incident and suggest the likely root cause: ',
  },
];

export const SAMPLE_DATA: SampleData[] = [
  {
    label: 'HLS Authentication',
    description: 'Explain HLS authentication.',
    text: 'Explain HLS authentication in DLMS/COSEM and when it should be used.',
    inputClass: 'query',
    category: 'security',
  },
  {
    label: 'Replay Protection',
    description: 'Explain replay protection.',
    text: 'How do replay protection and frame counters work in DLMS security?',
    inputClass: 'query',
    category: 'security',
  },
  {
    label: 'AARE Reject',
    description: 'Explain an AARE reject.',
    text: 'AARE association rejected, diagnostic 6 - what does this usually mean?',
    inputClass: 'query',
    category: 'security',
  },
  {
    label: 'HDLC Frame',
    description: 'Decode a frame.',
    text: '7EA0210002002303F17B2B80C401C100BE1004800A0601602801FF000000065FF00000008040FF6E7E',
    inputClass: 'hex_frame',
    category: 'decode',
  },
  {
    label: 'Alarm Code',
    description: 'Analyze an alarm code.',
    text: '0x1342',
    inputClass: 'alarm_code',
    category: 'incident',
  },
  {
    label: 'XML Trace',
    description: 'Inspect an XML trace.',
    text: '<Event timestamp="2024-01-15T10:30:00Z"><Alarm code="0x1342" severity="critical"/><Source device="DCU-01"/></Event>',
    inputClass: 'xml_trace',
    category: 'incident',
  },
  {
    label: 'Log Block',
    description: 'Analyze logs.',
    text: '2024-01-15 10:30:00 [PLC] [ERROR] Connection lost to meter 12345\n2024-01-15 10:30:01 [WAN] [WARN] Retry attempt 1/3\n2024-01-15 10:30:05 [PLC] [INFO] Reconnection successful',
    inputClass: 'log_block',
    category: 'incident',
  },
];

export const CATEGORY_ICONS: Record<UiConversationCategory, string> = {
  security: lockClosedOutline,
  decode: codeSlashOutline,
  incident: warningOutline,
  general: informationCircleOutline,
};
