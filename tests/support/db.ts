import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Client } from 'pg';

/** Reads the git-ignored repo-root .env (local or CI-generated). Values are never logged. */
export function localEnv(): Record<string, string> {
  const file = join(__dirname, '..', '..', '.env');
  if (!existsSync(file)) return {};
  return Object.fromEntries(
    readFileSync(file, 'utf8')
      .split(/\r?\n/)
      .filter((l) => /^[A-Z0-9_]+=/.test(l))
      .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]),
  );
}

/**
 * Test-only, read-only DB assertion helper: connects as the runtime app role (DML only) and runs one SELECT.
 * Used to prove persistence independently of the UI.
 */
export async function queryDb<T extends Record<string, unknown>>(sql: string, params: unknown[]): Promise<T[]> {
  const env = { ...localEnv(), ...process.env };
  const client = new Client({
    host: '127.0.0.1',
    port: Number(env['POSTGRES_HOST_PORT'] ?? 5432),
    database: env['POSTGRES_DB'] ?? 'chatplatform',
    user: env['POSTGRES_APP_USER'] ?? 'platform_app',
    password: env['POSTGRES_APP_PASSWORD'],
    options: '-c search_path=platform -c default_transaction_read_only=on',
  });
  await client.connect();
  try {
    return (await client.query<T>(sql, params)).rows;
  } finally {
    await client.end();
  }
}
