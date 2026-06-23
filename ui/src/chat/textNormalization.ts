const EM_DASH = '\u2014';
const SECTION_SIGN = '\u00A7';
const CHECK_MARK = '\u2705';
const CROSS_MARK = '\u274C';
const MIDDLE_DOT = '\u00B7';
const MULTIPLICATION_SIGN = '\u00D7';
const ELLIPSIS = '\u2026';

const COMMON_REPLACEMENTS: Array<[RegExp, string]> = [
  [new RegExp('\u00E2\u20AC\u201D', 'g'), EM_DASH],
  [new RegExp('\u00C2\u00A7', 'g'), SECTION_SIGN],
  [new RegExp('\u00E2\u0153\u2026', 'g'), CHECK_MARK],
  [new RegExp('\u00C2\u00B7', 'g'), MIDDLE_DOT],
  [new RegExp('\u00C3\u2014', 'g'), MULTIPLICATION_SIGN],
  [/ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â[\u009d\uFFFD]?/g, EM_DASH],
  [/Ã¢â‚¬â€[\u009d\uFFFD]?/g, EM_DASH],
  [/â€”/g, EM_DASH],
  [/Ãƒâ€šÃ‚Â?§/g, SECTION_SIGN],
  [/Ã‚Â§/g, SECTION_SIGN],
  [/Â§/g, SECTION_SIGN],
  [/Ã¢Å“â€¦/g, CHECK_MARK],
  [/âœ…/g, CHECK_MARK],
  [/âŒ/g, CROSS_MARK],
  [/Â·/g, MIDDLE_DOT],
  [/Ã—/g, MULTIPLICATION_SIGN],
  [/â€¦/g, ELLIPSIS],
  [/â€œ|â€/g, '"'],
  [/â€™|â€˜/g, '\''],
  [/\uFFFD/g, ' '],
];

export function normalizeVisibleText(value: string): string {
  let normalized = value;

  for (const [pattern, replacement] of COMMON_REPLACEMENTS) {
    normalized = normalized.replace(pattern, replacement);
  }

  return normalized;
}

export function normalizeVisibleBlock(value: string): string {
  return normalizeVisibleText(value)
    .replace(/\r\n/g, '\n')
    .replace(/\\r\\n/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/([.!?])\s*Sources:\s*/gi, '$1\n\nSources: ')
    .replace(/([^\n])\nSources:\s*/g, '$1\n\nSources: ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/\s+([.,;:])/g, '$1')
    .trim();
}
