import { execFileSync } from 'node:child_process';
import { join } from 'node:path';

const repoRoot = join(__dirname, '..', '..');
const composeFiles = ['-f', 'docker-compose.yml', '-f', 'docker-compose.apps.yml'];

/** True when a Docker daemon answers; resilience tests that stop containers skip (with a reason) otherwise. */
export function dockerAvailable(): boolean {
  try {
    execFileSync('docker', ['info'], { stdio: 'ignore', timeout: 15_000 });
    return true;
  } catch {
    return false;
  }
}

function compose(...args: string[]) {
  execFileSync('docker', ['compose', ...composeFiles, ...args], { cwd: repoRoot, stdio: 'inherit', timeout: 120_000 });
}

export const stopService = (service: string) => compose('stop', service);
export const startService = (service: string) => compose('start', service);
