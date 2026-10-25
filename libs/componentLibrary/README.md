# componentLibrary

Two **independent** libraries that share only design tokens (no shared runtime).

| Entry point | Nx project | Framework | Components |
|---|---|---|---|
| `@chat-platform/component-library-react` (`react/src/index.ts`) | `component-library-react` | React 19 | `Button`, `StatusBadge` |
| `@chat-platform/component-library-web` (`web/src/index.ts`) | `component-library-web` | Lit 3 | `<cp-status-badge>` (`CpStatusBadge`, `defineCpStatusBadge()`) |

The React lib must not import Lit code and the web lib must not import React/Next (enforced by Nx tags
`platform:react` / `platform:web`).

## Usage

```tsx
import { Button, StatusBadge } from '@chat-platform/component-library-react';
<StatusBadge label="Backend API" status="up" />   // status: loading | up | degraded | down
<Button variant="secondary" onClick={retry}>Re-check</Button>
```

```ts
import { defineCpStatusBadge } from '@chat-platform/component-library-web';
defineCpStatusBadge(); // idempotent
// <cp-status-badge label="Widget" status="up"></cp-status-badge>
```

Both render text + colour (`role="status"`, `aria-live="polite"`), use semantic tokens, and show a visible focus
ring (`--cp-focus-ring`). Tests: `pnpm nx test component-library-react`, `pnpm nx test component-library-web`.
