# designsystem

Framework-neutral design tokens as CSS custom properties. Consumed by the Next.js dashboard, the Lit widget and
both component libraries.

## Use

```ts
import '@chat-platform/designsystem/tokens.css'; // once per app (global CSS)
import { cssVar } from '@chat-platform/designsystem'; // optional TS helper: cssVar('primary')
```

Lit/shadow DOM: custom properties inherit across the shadow boundary from the host document, so shadow styles use
`var(--cp-color-primary)` directly (verified by the Playwright test *design tokens from the document reach shadow
DOM styles*). An embedding page must load `tokens.css`.

## Files

| File | Content |
|---|---|
| `tokens/primitives.css` | raw palette, spacing (4px base), font stacks/sizes, radii, border/focus widths |
| `tokens/semantic.css` | light theme (default): background, surface, text, text-muted, border, primary, success, warning, danger, focus-ring, spacing/radius aliases |
| `tokens/themes/dark.css` | dark overrides, opt-in with `data-theme="dark"` on any ancestor |
| `tokens/index.css` | public entry (imports all layers) |

## Rules

- Naming: `--cp-<category>-<name>[-<step>]`. Components use **semantic** tokens only.
- Semantic tokens reference primitives; no hard-coded colours (enforced by `src/tokens.spec.ts`).
- Backwards compatibility: adding tokens is non-breaking; renaming/removing is breaking — add the new token,
  keep the old one as an alias for one release, then remove.
- Theme overrides change semantic tokens only, never primitives.
- Fonts: system font stacks only; no third-party font CDN. Self-host licensed fonts if a brand font is adopted.
- Accessibility: text/background pairs must meet WCAG 2.2 AA (4.5:1 body, 3:1 large text and UI). The current
  light pairs (`neutral-900` on `neutral-0/50`, `neutral-600` muted, `blue-500` with white) and dark pairs were
  chosen for AA; re-check with a contrast checker whenever palette values change. Status is never conveyed by
  colour alone (badges include text).

Tests: `pnpm nx test designsystem`.
