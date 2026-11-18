import { guestStorageKey } from '../api/client';
import { mockAuth } from '../api/mock/auth';
import type { UserMe } from '../api/auth';
export function authenticate() {
  const result = mockAuth('/auth/signup', 'POST', null, {
    loginId: 'test_owner',
    password: 'safe12345',
    nickname: '테스트 사장님',
  }) as { token: string; user: UserMe };
  localStorage.setItem(guestStorageKey, result.token);
  return result;
}
export function ownFixture(postIds: number[] = [], commentIds: number[] = []) {
  const raw = localStorage.getItem('sss-mock-board-v1');
  if (!raw) throw new Error('게시판 픽스처를 먼저 조회해 주세요.');
  const db = JSON.parse(raw);
  const token = localStorage.getItem(guestStorageKey);
  const user = mockAuth('/auth/me', 'GET', token) as UserMe;
  for (const post of db.posts)
    if (postIds.includes(post.item.postId)) {
      post.userId = user.userId;
      post.item.authorNickname = user.nickname;
    }
  for (const comment of db.comments)
    if (commentIds.includes(comment.item.commentId)) {
      comment.userId = user.userId;
      comment.item.authorNickname = user.nickname;
    }
  localStorage.setItem('sss-mock-board-v1', JSON.stringify(db));
}
