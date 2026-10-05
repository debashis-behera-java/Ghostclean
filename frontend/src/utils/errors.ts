import { AxiosError } from 'axios';
import type { ApiErrorBody } from '../types/api';

/**
 * Centralized, user-friendly error mapping. Backend envelopes carry
 * {code, message}; we surface safe text only — never stack traces, SQL,
 * tokens, or raw payloads.
 */

/** Map a bare backend error code (e.g. from an OAuth redirect) to safe text. */
export function codeToMessage(code: string | null): string {
  switch (code) {
    case 'OAUTH_DENIED':
      return 'The connection was not granted at the provider. Try again and approve access.';
    case 'OAUTH_STATE_MISMATCH':
      return 'The connection attempt expired or was tampered with. Please try again.';
    case 'PROVIDER_ERROR':
      return 'The provider returned an error. Please try again later.';
    case 'OAUTH_PROVIDER_DISABLED':
      return 'This provider is currently disabled.';
    default:
      return 'The provider did not grant access, or the attempt expired.';
  }
}

function envelopeOf(error: unknown): { code?: string; message?: string; status?: number } {
  if (error instanceof AxiosError) {
    const body = error.response?.data as ApiErrorBody | undefined;
    return { code: body?.code, message: body?.message, status: error.response?.status };
  }
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { data?: ApiErrorBody; status?: number } }).response;
    return { code: response?.data?.code, message: response?.data?.message, status: response?.status };
  }
  return {};
}

function isNetworkFailure(error: unknown): boolean {
  if (error instanceof AxiosError) return !error.response;
  if (error instanceof Error) return false;
  return false;
}

export function friendlyErrorMessage(error: unknown): string {
  const { code, message, status } = envelopeOf(error);
  if (isNetworkFailure(error)) {
    const axiosCode = error instanceof AxiosError ? error.code : undefined;
    return axiosCode === 'ECONNABORTED'
      ? 'The request timed out. Check your connection and try again.'
      : 'We could not reach the GhostClean server. Check your connection and try again.';
  }
  switch (code) {
    case 'INVALID_CREDENTIALS':
      return 'Incorrect email or password.';
    case 'ACCOUNT_LOCKED':
      return 'This account is temporarily locked after too many failed attempts. Try again later.';
    case 'EMAIL_NOT_VERIFIED':
      return 'Verify your email address before continuing.';
    case 'VALIDATION_ERROR':
      return message ?? 'Some input was invalid. Check the form and try again.';
    case 'NOT_FOUND':
      return 'The requested item was not found. It may belong to another account.';
    case 'FORBIDDEN':
    case 'UNAUTHORIZED':
      return 'You are not authorized for this action. Please log in again.';
    case 'RATE_LIMITED':
      return 'Too many attempts. Please wait a while and try again.';
    case 'REQUEST_CONFLICT':
      return message ?? 'This action conflicts with the current state. Refresh and try again.';
    case 'REQUEST_EXPIRED':
      return 'This request has expired. Create a new one to continue.';
    case 'DESTINATION_UNKNOWN':
      return 'No verified destination is known for this service. Manual review is required.';
    case 'MANUAL_REVIEW_REQUIRED':
      return 'This route requires manual handling. GhostClean cannot dispatch it automatically.';
    case 'SCAN_CONFLICT':
      return 'A scan is already running for this mailbox.';
    case 'GOOGLE_CONNECTION_REQUIRED':
    case 'MICROSOFT_CONNECTION_REQUIRED':
      return 'Connect your mailbox before scanning.';
    case 'GOOGLE_REAUTH_REQUIRED':
    case 'MICROSOFT_REAUTH_REQUIRED':
    case 'SCOPE_INSUFFICIENT':
      return 'The mailbox connection needs attention. Reconnect or refresh it, then try again.';
    case 'OAUTH_DENIED':
      return 'The connection was not granted at the provider. Try again and approve access.';
    case 'INVALID_TOKEN':
    case 'EXPIRED_TOKEN':
      return 'This link is invalid, expired, or already used. Request a new one.';
    default:
      break;
  }
  if (status === 400) return message ?? 'The request was invalid.';
  if (status === 401) return 'Your session has expired. Please log in again.';
  if (status === 403) return 'You do not have permission for this action.';
  if (status === 404) return 'Not found.';
  if (status === 409) return message ?? 'Conflict with the current state. Refresh and try again.';
  if (status === 422) return message ?? 'The request could not be processed.';
  if (status === 429) return 'Too many attempts. Please wait and try again.';
  if (status !== undefined && status >= 500)
    return 'Something went wrong on our side. Please try again later.';
  return message ?? 'Something went wrong. Please try again.';
}
