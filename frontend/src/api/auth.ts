import { request, guestStorageKey } from './client';
import type { UserMe } from './mock/auth';
export type { UserMe } from './mock/auth';
export function clearAuth() {
  const existed = localStorage.getItem(guestStorageKey);
  localStorage.removeItem(guestStorageKey);
  if (existed) window.dispatchEvent(new Event('authchange'));
}
export const authApi = {
  me: () => request<UserMe>('/auth/me'),
  save: (nickname: string) => request<UserMe>('/auth/me', 'PATCH', { nickname }),
  password: (currentPassword: string, newPassword: string) =>
    request('/auth/me/password', 'PUT', { currentPassword, newPassword }),
  remove: async (password: string) => {
    await request('/auth/me', 'DELETE', { password });
    clearAuth();
  },
  logout: async () => {
    try {
      await request('/auth/logout', 'POST');
    } finally {
      clearAuth();
    }
  },
  enter: async (signup: boolean, body: Record<string, string>) => {
    const result = await request<{ token: string; user: UserMe }>(
      signup ? '/auth/signup' : '/auth/login',
      'POST',
      body,
    );
    localStorage.setItem(guestStorageKey, result.token);
    window.dispatchEvent(new Event('authchange'));
    return result.user;
  },
};
