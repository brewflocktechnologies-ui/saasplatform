// Cross-platform Maven Wrapper launcher for Nx targets: runs mvnw.cmd on Windows and ./mvnw elsewhere,
// in the current working directory (set by the Nx target's `cwd`). Maven stays the source of truth.
import { spawnSync } from 'node:child_process';

const win = process.platform === 'win32';
const result = spawnSync(win ? '.\\mvnw.cmd' : './mvnw', ['-B', ...process.argv.slice(2)], {
  stdio: 'inherit',
  shell: win,
});
process.exit(result.status ?? 1);
