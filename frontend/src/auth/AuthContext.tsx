import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { onAuthFailureSubscribe } from '../api/client';
import { userApi } from '../api/userApi';
import type { UserProfile } from '../types/api';
import { authService } from './authService';

interface AuthState {
  user: UserProfile | null;
  initializing: boolean;
  login: (email: string, password: string) => Promise<UserProfile>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }): React.JSX.Element {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [initializing, setInitializing] = useState(true);

  useEffect(() => {
    let cancelled = false;
    authService
      .restore()
      .then((profile) => {
        if (!cancelled) setUser(profile);
      })
      .finally(() => {
        if (!cancelled) setInitializing(false);
      });
    const unsubscribe = onAuthFailureSubscribe(() => setUser(null));
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const profile = await authService.login(email, password);
    setUser(profile);
    return profile;
  }, []);

  const logout = useCallback(async () => {
    await authService.logout();
    setUser(null);
  }, []);

  const refreshUser = useCallback(async () => {
    try {
      setUser(await userApi.me());
    } catch {
      setUser(null);
    }
  }, []);

  const value = useMemo(
    () => ({ user, initializing, login, logout, refreshUser }),
    [user, initializing, login, logout, refreshUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
