'use client';

import { useCallback, useEffect, useState } from 'react';
import { Button, StatusBadge, type Status } from '@chat-platform/component-library-react';

// Shapes follow libs/contracts/openapi/platform-api.yaml.
interface DependencyStatus {
  status: 'UP' | 'DOWN';
  detail?: string;
}
interface StatusResponse {
  version: string;
  environment: string;
  status: 'UP' | 'DEGRADED';
  dependencies: Record<string, DependencyStatus>;
}
interface BootstrapRecord {
  setupKey: string;
  message: string;
  createdAt: string;
}

type Check<T> = { state: 'loading' } | { state: 'ok'; data: T } | { state: 'error'; message: string };

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { cache: 'no-store', signal: AbortSignal.timeout(5000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as T;
}

const dep = (s: Check<StatusResponse>, name: string): Status => {
  if (s.state === 'loading') return 'loading';
  if (s.state === 'error') return 'down';
  return s.data.dependencies[name]?.status === 'UP' ? 'up' : 'down';
};

export function ServiceStatus({ apiBaseUrl }: { apiBaseUrl: string }) {
  const [status, setStatus] = useState<Check<StatusResponse>>({ state: 'loading' });
  const [bootstrap, setBootstrap] = useState<Check<BootstrapRecord>>({ state: 'loading' });

  const refresh = useCallback(() => {
    setStatus({ state: 'loading' });
    setBootstrap({ state: 'loading' });
    getJson<StatusResponse>(`${apiBaseUrl}/api/v1/status`).then(
      (data) => setStatus({ state: 'ok', data }),
      (e: Error) => setStatus({ state: 'error', message: e.message }),
    );
    getJson<BootstrapRecord>(`${apiBaseUrl}/api/v1/bootstrap`).then(
      (data) => setBootstrap({ state: 'ok', data }),
      (e: Error) => setBootstrap({ state: 'error', message: e.message }),
    );
  }, [apiBaseUrl]);

  useEffect(refresh, [refresh]);

  const api: Status = status.state === 'loading' ? 'loading' : status.state === 'ok' ? 'up' : 'down';

  return (
    <section className="card" aria-labelledby="status-heading" aria-busy={status.state === 'loading'}>
      <h2 id="status-heading">Service status</h2>
      <ul className="status-list" data-testid="status-list">
        <li data-testid="status-backend">
          <StatusBadge label="Backend API" status={api} />
        </li>
        <li data-testid="status-database">
          <StatusBadge label="Database" status={dep(status, 'database')} />
        </li>
        <li data-testid="status-centrifugo">
          <StatusBadge label="Centrifugo" status={dep(status, 'centrifugo')} />
        </li>
      </ul>

      {status.state === 'error' && (
        <p className="error" role="alert" data-testid="status-error">
          Cannot reach the backend at <code>{apiBaseUrl}</code> ({status.message}). Is it running?
        </p>
      )}

      {status.state === 'ok' && (
        <dl>
          <dt>Version</dt>
          <dd>{status.data.version}</dd>
          <dt>Database</dt>
          <dd>{status.data.dependencies['database']?.detail ?? '—'}</dd>
          <dt>Bootstrap record</dt>
          <dd data-testid="bootstrap-record">
            {bootstrap.state === 'ok'
              ? `${bootstrap.data.setupKey}: ${bootstrap.data.message}`
              : bootstrap.state === 'loading'
                ? 'Loading…'
                : `unavailable (${bootstrap.message})`}
          </dd>
        </dl>
      )}

      <p>
        <Button variant="secondary" onClick={refresh} disabled={status.state === 'loading'}>
          Re-check
        </Button>
      </p>
    </section>
  );
}
