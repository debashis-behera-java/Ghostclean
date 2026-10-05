import { setAccessToken } from '../api/client';
import { authApi } from '../api/authApi';
import { userApi } from '../api/userApi';
import type { UserProfile } from '../types/api';

/**
 * Auth orchestration. The access token is kept in memory (via api/client)
 * and never persisted to localStorage — a page reload re-establishes the
 * session through the httpOnly refresh cookie. The refresh token itself is
 * never visible to JavaScript. Documented limitation: in-memory tokens do
 * not survive reloads without a successful silent refresh; logged-out or
 * expired sessions redirect to /login.
 */
export const authService = {
  async login(email: string, password: string): Promise<UserProfile> {
    const res = await authApi.login(email, password);
    setAccessToken(res.accessToken);
    return userApi.me();
  },
  async logout(): Promise<void> {
    try {
      await authApi.logout();
    } finally {
      setAccessToken(null);
    }
  },
  /** Silent session restore on app boot. Returns null when no session exists. */
  async restore(): Promise<UserProfile | null> {
    try {
      const res = await authApi.refresh();
      setAccessToken(res.accessToken);
      return await userApi.me();
    } catch {
      setAccessToken(null);
      return null;
    }
  },
  clearLocal(): void {
    setAccessToken(null);
  },
};
