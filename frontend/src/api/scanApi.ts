import { api } from './client';
import type { ScanRecord } from '../types/api';

export const scanApi = {
  /** Starts a bounded scan of an owned connection. Backend runs it synchronously. */
  start(connectionId: string, idempotencyKey?: string): Promise<ScanRecord> {
    return api
      .post(
        '/api/v1/scans',
        { connectionId },
        idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
      )
      .then((r) => r.data);
  },
  list(): Promise<ScanRecord[]> {
    return api.get('/api/v1/scans').then((r) => r.data);
  },
  get(id: string): Promise<ScanRecord> {
    return api.get(`/api/v1/scans/${id}`).then((r) => r.data);
  },
  cancel(id: string): Promise<ScanRecord> {
    return api.post(`/api/v1/scans/${id}/cancel`).then((r) => r.data);
  },
  remove(id: string): Promise<void> {
    return api.delete(`/api/v1/scans/${id}`).then(() => undefined);
  },
};
