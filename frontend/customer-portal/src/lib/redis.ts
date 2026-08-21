import 'server-only';
import Redis from 'ioredis';
import type { IdempotencyStore } from '@/lib/idempotency';

/**
 * One ioredis connection per server process, kept on globalThis so Next's dev-mode module
 * reloading does not open a new connection on every edit. Redis is already provisioned in
 * infra/docker-compose.yml — this adds no infrastructure.
 */
const globalForRedis = globalThis as unknown as { portalRedis?: Redis };

export function redis(): Redis {
  if (!globalForRedis.portalRedis) {
    globalForRedis.portalRedis = new Redis(process.env.REDIS_URL!);
  }
  return globalForRedis.portalRedis;
}

export function redisIdempotencyStore(): IdempotencyStore {
  const client = redis();
  return {
    async setIfAbsent(key, value, ttlSeconds) {
      const reply = await client.set(key, value, 'EX', ttlSeconds, 'NX');
      return reply === 'OK';
    },
    async get(key) { return client.get(key); },
    async set(key, value) {
      // Preserve the remaining TTL rather than resetting it: KEEPTTL keeps the claim window
      // anchored to the original request, not to when the outcome happened to arrive.
      await client.set(key, value, 'KEEPTTL');
    },
    async del(key) { await client.del(key); },
  };
}
