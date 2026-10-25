import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cssVar, semanticColorTokens } from './index';

const tokensDir = join(import.meta.dirname, '..', 'tokens');
const read = (file: string) => readFileSync(join(tokensDir, file), 'utf8');

function declarations(css: string): Map<string, string> {
  const map = new Map<string, string>();
  for (const m of css.matchAll(/(--cp-[a-z0-9-]+)\s*:\s*([^;]+);/g)) map.set(m[1], m[2].trim());
  return map;
}

describe('design tokens', () => {
  const primitives = declarations(read('primitives.css'));
  const semantic = declarations(read('semantic.css'));
  const dark = declarations(read('themes/dark.css'));

  it('defines every semantic colour token in the light and dark themes', () => {
    for (const token of semanticColorTokens) {
      expect(semantic.has(`--cp-color-${token}`), `light: ${token}`).toBe(true);
      expect(dark.has(`--cp-color-${token}`), `dark: ${token}`).toBe(true);
    }
  });

  it('semantic tokens reference existing tokens instead of hard-coded colours', () => {
    for (const [name, value] of [...semantic, ...dark]) {
      expect(value, name).not.toMatch(/#[0-9a-f]{3,8}\b|rgb\(|hsl\(/i);
      for (const ref of value.matchAll(/var\((--cp-[a-z0-9-]+)\)/g)) {
        expect(primitives.has(ref[1]) || semantic.has(ref[1]), `${name} -> ${ref[1]}`).toBe(true);
      }
    }
  });

  it('index.css imports all token layers', () => {
    const index = read('index.css');
    for (const f of ['primitives.css', 'semantic.css', 'themes/dark.css']) expect(index).toContain(f);
  });

  it('cssVar builds a semantic custom property reference', () => {
    expect(cssVar('primary')).toBe('var(--cp-color-primary)');
  });
});
