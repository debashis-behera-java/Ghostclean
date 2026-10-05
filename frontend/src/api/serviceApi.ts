import { api } from './client';
import type { ServiceDetail, ServiceSummary } from '../types/api';

export const serviceApi = {
  list(): Promise<ServiceSummary[]> {
    return api.get('/api/v1/services').then((r) => r.data);
  },
  get(slug: string): Promise<ServiceDetail> {
    return api.get(`/api/v1/services/${encodeURIComponent(slug)}`).then((r) => r.data);
  },
};
