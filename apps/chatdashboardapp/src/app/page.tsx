import { ServiceStatus } from './service-status';

// Read browser-facing config per request so one container image works in every environment.
export const dynamic = 'force-dynamic';

export default function Index() {
  const apiBaseUrl = process.env.PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
  const widgetDemoUrl = process.env.PUBLIC_WIDGET_DEMO_URL ?? 'http://localhost:5173';
  const environment = process.env.APP_ENVIRONMENT ?? 'local';

  return (
    <main className="page">
      <header className="page-header">
        <h1>Chat Platform — Local Development</h1>
        <p className="muted">
          Environment: <span className="env" data-testid="environment">{environment}</span> · Scaffold
          validation page, not a production admin dashboard.
        </p>
      </header>

      <div className="grid">
        <ServiceStatus apiBaseUrl={apiBaseUrl} />

        <section className="card" aria-labelledby="arch-heading">
          <h2 id="arch-heading">Architecture overview</h2>
          <dl>
            <dt>Dashboard</dt>
            <dd>Next.js (this app) · <code>apps/chatdashboardapp</code></dd>
            <dt>Widget</dt>
            <dd>Lit web component · <code>apps/chatwidget</code></dd>
            <dt>Backend</dt>
            <dd>Spring Boot modular monolith · <code>{apiBaseUrl}</code></dd>
            <dt>Database</dt>
            <dd>PostgreSQL, schema via dedicated Flyway runner</dd>
            <dt>Real-time</dt>
            <dd>Centrifugo (no channels/auth yet)</dd>
            <dt>Telemetry</dt>
            <dd>OTel Collector → Tempo / Loki, Prometheus, Grafana</dd>
          </dl>
        </section>

        <section className="card" aria-labelledby="widget-heading">
          <h2 id="widget-heading">Chat widget demo</h2>
          <p className="muted">
            The embeddable <code>&lt;chat-platform-widget&gt;</code> runs as its own app.
          </p>
          <a href={widgetDemoUrl} target="_blank" rel="noreferrer" data-testid="widget-demo-link">
            Open the widget demo
          </a>
        </section>
      </div>
    </main>
  );
}
