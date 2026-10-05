import { api } from './client';
import type { UserProfile } from '../types/api';

export const userApi = {
  me(): Promise<UserProfile> {
    return api.get('/api/v1/users/me').then((r) => r.data);
  },
};
