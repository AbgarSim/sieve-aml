import fs from 'node:fs';
import path from 'node:path';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Serves snapshot files under /data/ in dev and preview: from SIEVE_DATA_DIR, else from
 * ../snapshot (the default output of `sieve snapshot`), else from the fictional sample/.
 */
function snapshotData(): Plugin {
  const dir = () => [process.env.SIEVE_DATA_DIR, path.resolve(__dirname, '../snapshot'), path.resolve(__dirname, 'sample')]
    .find(d => d && fs.existsSync(path.join(d, 'overview.json')))!;
  const serve = (server: { middlewares: { use: (p: string, h: (req: { url?: string }, res: NodeJS.WritableStream & { setHeader: (k: string, v: string) => void; statusCode: number }, next: () => void) => void) => void } }) =>
    server.middlewares.use('/data', (req, res, next) => {
      const root = dir(), file = path.join(root, decodeURIComponent((req.url ?? '').split('?')[0]));
      if (!file.startsWith(root) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return next();
      res.setHeader('Content-Type', 'application/json');
      fs.createReadStream(file).pipe(res);
    });
  return {
    name: 'sieve-snapshot-data',
    configureServer(server) { console.log(`  snapshot data: ${dir()}`); serve(server); },
    configurePreviewServer(server) { serve(server); },
  };
}

export default defineConfig({
  plugins: [react(), snapshotData()],
  base: './',
  server: { proxy: { '/api': 'http://localhost:8080' } },
});
