import { render, screen, waitFor } from '@testing-library/react';
import { ServiceStatus } from '../src/app/service-status';

const ok = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));

describe('ServiceStatus', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('starts in a loading state (never a fake green)', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)));
    render(<ServiceStatus apiBaseUrl="http://api.test" />);
    expect(screen.getByText('Backend API: Checking…')).toBeTruthy();
  });

  it('shows healthy dependencies and the bootstrap record from the API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        url.endsWith('/status')
          ? ok({
              version: '0.1.0',
              environment: 'local',
              status: 'UP',
              dependencies: { database: { status: 'UP', detail: 'schema version 1' }, centrifugo: { status: 'UP' } },
            })
          : ok({ setupKey: 'local-poc', message: 'hello', createdAt: '2026-01-01T00:00:00Z' }),
      ),
    );
    render(<ServiceStatus apiBaseUrl="http://api.test" />);
    await waitFor(() => expect(screen.getByText('Backend API: Healthy')).toBeTruthy());
    expect(screen.getByText('Database: Healthy')).toBeTruthy();
    expect(screen.getByText('Centrifugo: Healthy')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('bootstrap-record').textContent).toBe('local-poc: hello'));
  });

  it('shows a failure state when the backend is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));
    render(<ServiceStatus apiBaseUrl="http://api.test" />);
    await waitFor(() => expect(screen.getByText('Backend API: Unavailable')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('Cannot reach the backend');
  });
});
