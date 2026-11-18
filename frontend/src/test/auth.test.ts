import { beforeEach, expect, it } from 'vitest';
import { authApi } from '../api/auth';
import { api, guestStorageKey } from '../api/client';
import { boardApi } from '../api/board';
import { validPassword } from '../api/mock/auth';
beforeEach(() => localStorage.clear());
const signup = () =>
  authApi.enter(true, { loginId: 'owner_1', nickname: '새 사장님', password: 'safe12345' });
it('게스트 전환은 같은 userId, 로그인과 로그아웃은 공용 토큰만 교체한다', async () => {
  await api.guest();
  const guest = await authApi.me();
  const member = await signup();
  expect(member.userId).toBe(guest.userId);
  expect(member.role).toBe('MEMBER');
  await authApi.logout();
  expect(localStorage.getItem(guestStorageKey)).toBeNull();
  await authApi.enter(false, { loginId: 'owner_1', password: 'safe12345' });
  expect((await authApi.me()).userId).toBe(guest.userId);
});
it('공개 읽기는 허용하고 게스트 쓰기는 MEMBER_REQUIRED', async () => {
  await api.guest();
  expect((await boardApi.posts()).totalElements).toBeGreaterThan(0);
  await expect(boardApi.like(2, true)).rejects.toMatchObject({
    status: 403,
    code: 'MEMBER_REQUIRED',
  });
});
it('회원이 작성한 글과 댓글은 현재 사용자 기준 소유권을 가진다', async () => {
  await signup();
  const post = await boardApi.createPost({ category: 'FREE', title: '테스트', content: '본문' });
  expect(post.isMine).toBe(true);
  expect((await boardApi.post(2)).isMine).toBe(false);
  const comment = await boardApi.createComment(post.postId, { content: '댓글' });
  expect((await boardApi.comments(post.postId)).content[0].isMine).toBe(true);
  await boardApi.deleteComment(post.postId, comment.commentId, 0);
});
it('중복 필드와 5회 실패 잠금, 비밀번호 형식을 검증한다', async () => {
  await signup();
  await expect(signup()).rejects.toMatchObject({ code: 'LOGIN_ID_TAKEN' });
  await authApi.logout();
  for (let i = 0; i < 4; i++)
    await expect(
      authApi.enter(false, { loginId: 'owner_1', password: 'wrong' }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
  await expect(
    authApi.enter(false, { loginId: 'owner_1', password: 'wrong' }),
  ).rejects.toMatchObject({ status: 423 });
  expect(validPassword('abcdefgh')).toBe(false);
  expect(validPassword('12345678')).toBe(false);
  expect(validPassword('good1234')).toBe(true);
});
it('비밀번호 변경은 다른 세션만 폐기하고 탈퇴는 현재 세션도 지운다', async () => {
  await signup();
  const first = localStorage.getItem(guestStorageKey)!;
  await authApi.enter(false, { loginId: 'owner_1', password: 'safe12345' });
  const current = localStorage.getItem(guestStorageKey)!;
  await authApi.password('safe12345', 'changed123');
  localStorage.setItem(guestStorageKey, first);
  await expect(authApi.me()).rejects.toMatchObject({ status: 401 });
  localStorage.setItem(guestStorageKey, current);
  expect((await authApi.me()).role).toBe('MEMBER');
  await authApi.remove('changed123');
  expect(localStorage.getItem(guestStorageKey)).toBeNull();
});
it('RECORD 없는 게임은 네트워크 오류 대신 404', async () => {
  await expect(api.summary(99999)).rejects.toMatchObject({ status: 404 });
});
