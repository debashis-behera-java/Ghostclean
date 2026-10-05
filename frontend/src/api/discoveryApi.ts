import { api } from './client';
import type { DiscoveredAccount, EvidenceRow } from '../types/api';

export const discoveryApi = {
  list(status?: string): Promise<DiscoveredAccount[]> {
    return api
      .get('/api/v1/discovered', { params: status ? { status } : {} })
      .then((r) => r.data);
  },
  get(id: string): Promise<DiscoveredAccount> {
    return api.get(`/api/v1/discovered/${id}`).then((r) => r.data);
  },
  review(id: string, decision: 'keep' | 'dismiss', reason?: string): Promise<DiscoveredAccount> {
    return api
      .post(`/api/v1/discovered/${id}/review`, { decision, reason })
      .then((r) => r.data);
  },
  evidence(id: string): Promise<EvidenceRow[]> {
    return api.get(`/api/v1/discovered/${id}/evidence`).then((r) => r.data);
  },
  purgeEvidence(id: string): Promise<{ accountRemoved: boolean }> {
    return api.delete(`/api/v1/discovered/${id}/evidence-purge`).then((r) => r.data);
  },
};
