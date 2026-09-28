import type { FastifyInstance } from 'fastify';
import { getNexavlinksAccount, getNexavlinksProgress, getNexavlinksSettings, saveNexavlinksSettings } from '../../services/nexavlinksService.js';

export async function nexavlinksRoutes(app: FastifyInstance) {
  app.get('/api/nexavlinks/settings', async () => getNexavlinksSettings());
  app.put<{ Body: { enabled?: unknown; target?: unknown } }>('/api/nexavlinks/settings', async (request, reply) => {
    try { return await saveNexavlinksSettings(request.body); }
    catch (error) { return reply.code(400).send({ message: error instanceof Error ? error.message : '设置保存失败' }); }
  });
  app.post<{ Params: { id: string } }>('/api/nexavlinks/accounts/:id/sync', async (request, reply) => {
    try {
      const row = await getNexavlinksAccount(Number(request.params.id));
      const progress = await getNexavlinksProgress([row], { force: true });
      return { success: true, progress: progress.get(row.account.id) };
    } catch (error) { return reply.code(400).send({ message: error instanceof Error ? error.message : '进度刷新失败' }); }
  });
}
