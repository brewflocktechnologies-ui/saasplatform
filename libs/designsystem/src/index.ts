/**
 * TypeScript view of the semantic token contract. The CSS files in ../tokens are the source of truth;
 * tokens.spec.ts keeps this list and the CSS in sync.
 */
export const semanticColorTokens = [
  'background',
  'surface',
  'text',
  'text-muted',
  'border',
  'primary',
  'primary-hover',
  'on-primary',
  'success',
  'warning',
  'danger',
  'focus-ring',
] as const;

export type SemanticColorToken = (typeof semanticColorTokens)[number];

/** `cssVar('primary')` -> `var(--cp-color-primary)` */
export function cssVar(token: SemanticColorToken): string {
  return `var(--cp-color-${token})`;
}
