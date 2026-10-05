import { api } from './client';
import type {
  AuthorizeResponse,
  GoogleApprovalQueueItem,
  GoogleApprovalStatus,
  OAuthConnection,
} from '../types/api';

export const oauthApi = {
  /** Starts a connection; backend returns the provider redirect URL. */
  authorize(provider: 'google' | 'microsoft', returnUrl?: string): Promise<AuthorizeResponse> {
    return api
      .get(`/api/v1/oauth/${provider}/authorize`, { params: returnUrl ? { returnUrl } : {} })
      .then((r) => r.data);
  },
  connections(): Promise<OAuthConnection[]> {
    return api.get('/api/v1/oauth/connections').then((r) => r.data);
  },
  disconnect(id: string): Promise<void> {
    return api.delete(`/api/v1/oauth/connections/${id}`).then(() => undefined);
  },
  refreshConnection(id: string): Promise<OAuthConnection> {
    return api.post(`/api/v1/oauth/connections/${id}/refresh`).then((r) => r.data);
  },
  /** GhostClean approval state for starting Google OAuth (never authorizes anything). */
  googleApprovalStatus(): Promise<GoogleApprovalStatus> {
    return api.get('/api/v1/google/connection/status').then((r) => r.data);
  },
  /** Creates (or returns) the owner's PENDING approval request. */
  requestGoogleApproval(): Promise<GoogleApprovalStatus> {
    return api.post('/api/v1/google/connection/request').then((r) => r.data);
  },
  cancelGoogleApproval(): Promise<GoogleApprovalStatus> {
    return api.post('/api/v1/google/connection/request/cancel').then((r) => r.data);
  },
  adminGoogleRequests(status?: string): Promise<GoogleApprovalQueueItem[]> {
    return api
      .get('/api/v1/admin/google/connection-requests', { params: status ? { status } : {} })
      .then((r) => r.data);
  },
  adminApproveGoogleRequest(id: string): Promise<GoogleApprovalQueueItem> {
    return api.post(`/api/v1/admin/google/connection-requests/${id}/approve`).then((r) => r.data);
  },
  adminRejectGoogleRequest(id: string, reason?: string): Promise<GoogleApprovalQueueItem> {
    return api
      .post(`/api/v1/admin/google/connection-requests/${id}/reject`, { reason })
      .then((r) => r.data);
  },
};
