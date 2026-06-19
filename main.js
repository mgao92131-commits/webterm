import { loadLocalEnv } from './shared/utils.js';

loadLocalEnv();

const mode = process.env.WEBTERM_MODE || 'direct';

if (mode === 'agent') {
  const { startAgent } = await import('./server/agent.js');
  startAgent();
} else {
  const { startServer } = await import('./server/direct.js');
  startServer();
}
