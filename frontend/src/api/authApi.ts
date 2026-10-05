import { api } from './client';
import type { LoginResponse, RegisterResponse } from '../types/api';

export const authApi = {
  register(email: string, password: string): Promise<RegisterResponse> {
    return api.post('/api/v1/auth/register', { email, password }).then((r) => r.data);
  },
  verify(token: string): Promise<void> {
    return api.post('/api/v1/auth/verify', { token }).then(() => undefined);
  },
  resendVerification(email: string): Promise<void> {
    return api.post('/api/v1/auth/verification/resend', { email }).then(() => undefined);
  },
  forgotPassword(email: string): Promise<{ message: string }> {
    return api.post('/api/v1/auth/password/forgot', { email }).then((r) => r.data);
  },
  resetPassword(token: string, newPassword: string): Promise<{ message: string }> {
    return api.post('/api/v1/auth/password/reset', { token, newPassword }).then((r) => r.data);
  },
  login(email: string, password: string): Promise<LoginResponse> {
    return api.post('/api/v1/auth/login', { email, password }).then((r) => r.data);
  },
  refresh(): Promise<LoginResponse> {
    return api.post('/api/v1/auth/refresh').then((r) => r.data);
  },
  logout(): Promise<void> {
    return api.post('/api/v1/auth/logout').then(() => undefined);
  },
};
