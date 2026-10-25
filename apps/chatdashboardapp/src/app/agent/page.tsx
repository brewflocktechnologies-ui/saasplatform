import { AgentInbox } from './agent-inbox';

// Browser-facing URLs are read per request so one image works everywhere. Never Compose hostnames here.
export const dynamic = 'force-dynamic';

export const metadata = { title: 'Agent inbox — Chat Platform (local POC)' };

export default function AgentPage() {
  const apiBaseUrl = process.env.PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
  const realtimeUrl = process.env.PUBLIC_CENTRIFUGO_WS_URL ?? 'ws://localhost:8000/connection/websocket';

  return (
    <main className="page">
      <p className="poc-banner" role="note">
        <strong>LOCAL POC — NOT REAL AUTHENTICATION.</strong> Fixed demo identity; do not deploy to UAT/production.
      </p>
      <header className="page-header">
        <h1>Agent inbox</h1>
        <p className="muted">
          <span data-testid="agent-id">Demo agent: agentbob</span> · <a href="/">Back to overview</a>
        </p>
      </header>
      <AgentInbox apiBaseUrl={apiBaseUrl} realtimeUrl={realtimeUrl} />
    </main>
  );
}
