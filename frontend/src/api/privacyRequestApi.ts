import { api } from './client';
import type {
  DispatchView,
  PrivacyRequestSummary,
  ServiceResponse,
  TrackingView,
} from '../types/api';

export const RESPONSE_OUTCOMES = [
  'completed',
  'rejected',
  'partial',
  'verification_required',
] as const;

export const privacyRequestApi = {
  create(
    discoveredServiceId: string,
    requestType?: string,
    jurisdiction?: string,
    idempotencyKey?: string,
  ): Promise<PrivacyRequestSummary> {
    return api
      .post(
        '/api/v1/privacy-requests',
        {
          discoveredServiceId,
          ...(requestType ? { requestType } : {}),
          ...(jurisdiction ? { jurisdiction } : {}),
        },
        idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
      )
      .then((r) => r.data);
  },
  list(status?: string): Promise<PrivacyRequestSummary[]> {
    return api
      .get('/api/v1/privacy-requests', { params: status ? { status } : {} })
      .then((r) => r.data);
  },
  get(id: string): Promise<PrivacyRequestSummary> {
    return api.get(`/api/v1/privacy-requests/${id}`).then((r) => r.data);
  },
  patch(id: string, subject?: string, body?: string): Promise<PrivacyRequestSummary> {
    return api
      .patch(`/api/v1/privacy-requests/${id}`, { ...(subject !== undefined ? { subject } : {}), ...(body !== undefined ? { body } : {}) })
      .then((r) => r.data);
  },
  /** Approves the exact current version. Approval alone never sends. */
  approve(id: string, version?: number): Promise<PrivacyRequestSummary> {
    return api
      .post(`/api/v1/privacy-requests/${id}/approve`, version !== undefined ? { version: String(version) } : {})
      .then((r) => r.data);
  },
  cancel(id: string): Promise<PrivacyRequestSummary> {
    return api.post(`/api/v1/privacy-requests/${id}/cancel`).then((r) => r.data);
  },
  tracking(id: string): Promise<TrackingView> {
    return api.get(`/api/v1/privacy-requests/${id}/tracking`).then((r) => r.data);
  },
  responses(id: string): Promise<ServiceResponse[]> {
    return api.get(`/api/v1/privacy-requests/${id}/responses`).then((r) => r.data);
  },
  recordResponse(id: string, outcome: string, summary?: string): Promise<ServiceResponse> {
    return api
      .post(`/api/v1/privacy-requests/${id}/responses`, { outcome, summary })
      .then((r) => r.data);
  },
  /**
   * Explicit dispatch confirmation. Called ONLY from the dispatch
   * confirmation modal after the user checks the understanding box.
   * Never call automatically, on load, after approval, or from background code.
   */
  dispatch(id: string, version?: number, idempotencyKey?: string): Promise<DispatchView> {
    return api
      .post(
        `/api/v1/privacy-requests/${id}/dispatch`,
        {
          ...(version !== undefined ? { version: String(version) } : {}),
          ...(idempotencyKey ? { idempotencyKey } : {}),
        },
        idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
      )
      .then((r) => r.data);
  },
  dispatchStatus(id: string): Promise<DispatchView> {
    return api.get(`/api/v1/privacy-requests/${id}/dispatch`).then((r) => r.data);
  },
  cancelDispatch(id: string): Promise<DispatchView> {
    return api.post(`/api/v1/privacy-requests/${id}/dispatch/cancel`).then((r) => r.data);
  },
};
