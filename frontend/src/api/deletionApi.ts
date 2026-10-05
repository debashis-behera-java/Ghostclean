import { api } from './client';
import type { DeletionPreview, DeletionRequestView } from '../types/api';

export const REQUEST_TYPES = [
  'DELETE_PERSONAL_DATA',
  'DELETE_ACCOUNT',
  'ACCESS_PERSONAL_DATA',
  'GENERAL_PRIVACY_REQUEST',
] as const;

export const JURISDICTIONS = ['GENERAL', 'GDPR', 'CCPA_CPRA'] as const;

export const deletionApi = {
  /** Side-effect-free preview. Never writes, never sends. */
  preview(
    discoveredServiceId: string,
    requestType?: string,
    jurisdiction?: string,
  ): Promise<DeletionPreview> {
    return api
      .post('/api/v1/deletion-requests/preview', {
        discoveredServiceId,
        ...(requestType ? { requestType } : {}),
        ...(jurisdiction ? { jurisdiction } : {}),
      })
      .then((r) => r.data);
  },
  /** Creates a template-versioned DRAFT. Never approves, never sends. */
  create(
    discoveredServiceId: string,
    requestType?: string,
    jurisdiction?: string,
    idempotencyKey?: string,
  ): Promise<DeletionRequestView> {
    return api
      .post(
        '/api/v1/deletion-requests',
        {
          discoveredServiceId,
          ...(requestType ? { requestType } : {}),
          ...(jurisdiction ? { jurisdiction } : {}),
        },
        idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
      )
      .then((r) => r.data);
  },
  get(id: string): Promise<DeletionRequestView> {
    return api.get(`/api/v1/deletion-requests/${id}`).then((r) => r.data);
  },
};
