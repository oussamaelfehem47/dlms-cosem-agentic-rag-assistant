import { normalizeVisibleBlock, normalizeVisibleText } from './textNormalization';

export interface TrailingSourcesParseResult {
  bodyText: string;
  citations: string[];
}

const TRAILING_SOURCES_RE = /\n{1,2}Sources:\s*([\s\S]*?)\s*$/;
const EM_DASH = '\u2014';
const SECTION_SIGN = '\u00A7';

function normalizeCitationArtifacts(text: string): string {
  return normalizeVisibleBlock(text);
}

function normalizeCitationLabel(citation: string): string {
  const normalized = normalizeVisibleText(
    normalizeCitationArtifacts(citation).trim().replace(/\s{2,}/g, ' ')
  );

  if (/^DLMS Standard\b/i.test(normalized)) {
    return normalized
      .replace(/^DLMS Standard\s+[^A-Za-z0-9§]+/i, `DLMS Standard ${EM_DASH} ${SECTION_SIGN}`)
      .replace(/^DLMS Standard\s+—\s*§*/i, `DLMS Standard ${EM_DASH} ${SECTION_SIGN}`);
  }

  if (/^Confluence\b/i.test(normalized)) {
    return normalized.replace(/^Confluence\s+[^A-Za-z0-9(]+/i, `Confluence ${EM_DASH} `);
  }

  return normalized;
}

export function extractTrailingSources(text: string): TrailingSourcesParseResult {
  const normalized = normalizeCitationArtifacts(text);
  const match = normalized.match(TRAILING_SOURCES_RE);
  if (!match || match.index === undefined) {
    return {
      bodyText: normalized,
      citations: [],
    };
  }

  const citations = match[1]
    .split(';')
    .map((citation) => normalizeCitationLabel(citation))
    .filter(Boolean);

  if (citations.length === 0) {
    return {
      bodyText: normalized,
      citations: [],
    };
  }

  return {
    bodyText: normalized.slice(0, match.index).trimEnd(),
    citations: Array.from(new Set(citations)),
  };
}
