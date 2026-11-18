import { api, ApiError, guestStorageKey, useMock } from './client';
import { mockBoardRequest } from './mock/board';
import type {
  CommentItem, CreateCommentRequest, CreatePostRequest, CreateReportRequest, MyPostListItem,
  PageQuery, PageResponse, PostDetail, PostListItem, PostSearchQuery, RecordPostLink,
  ReportCreated, UpdateCommentRequest, UpdatePostRequest,
} from './types';

type BoardAuth = 'none' | 'optional' | 'required';

async function boardRequest<T>(
  path: string, auth: BoardAuth, method = 'GET', body?: unknown, signal?: AbortSignal,
): Promise<T> {
  const token = auth === 'required' ? await api.guest()
    : auth === 'optional' ? localStorage.getItem(guestStorageKey) : null;
  async function send(guestToken: string | null): Promise<Response> {
    if (useMock) return mockBoardRequest(path, method, guestToken, body);
    try {
      return await fetch(`/api${path}`, {
        method, signal,
        headers: {
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(guestToken ? { 'X-Guest-Token': guestToken } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (error) {
      if (signal?.aborted) throw error;
      throw new ApiError(0, '서버 연결에 실패했습니다. 잠시 후 다시 시도해 주세요.');
    }
  }
  let response = await send(token);
  if (auth === 'optional' && token && response.status === 401) response = await send(null);
  if (response.status === 204) return undefined as T;
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(response.status,
      typeof error?.message === 'string' ? error.message : '서버 연결에 실패했습니다.',
      typeof error?.code === 'string' ? error.code : undefined);
  }
  return response.json();
}

function withQuery(path: string, query: Record<string, string | number | undefined>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) if (value !== undefined) params.set(key, String(value));
  return params.size ? `${path}?${params}` : path;
}

const posts = '/board/posts';
const post = (postId: number) => `${posts}/${postId}`;
const comments = (postId: number) => `${post(postId)}/comments`;

export const boardApi = {
  posts: (query: PostSearchQuery = {}, signal?: AbortSignal) =>
    boardRequest<PageResponse<PostListItem>>(withQuery(posts, { ...query }), 'none', 'GET', undefined, signal),
  post: (postId: number) => boardRequest<PostDetail>(post(postId), 'optional'),
  createPost: (body: CreatePostRequest) => boardRequest<PostDetail>(posts, 'required', 'POST', body),
  updatePost: (postId: number, body: UpdatePostRequest) => boardRequest<PostDetail>(post(postId), 'required', 'PUT', body),
  deletePost: (postId: number, version: number) => boardRequest<void>(withQuery(post(postId), { version }), 'required', 'DELETE'),
  myPosts: (query: PostSearchQuery = {}) =>
    boardRequest<PageResponse<MyPostListItem>>(withQuery('/board/me/posts', { ...query }), 'required'),
  recordLink: (gameId: number) => boardRequest<RecordPostLink | undefined>(`/board/records/${gameId}`, 'none'),
  comments: (postId: number, query: PageQuery = {}) =>
    boardRequest<PageResponse<CommentItem>>(withQuery(comments(postId), { ...query }), 'optional'),
  replies: (postId: number, commentId: number, query: PageQuery = {}) =>
    boardRequest<PageResponse<CommentItem>>(withQuery(`${comments(postId)}/${commentId}/replies`, { ...query }), 'optional'),
  createComment: (postId: number, body: CreateCommentRequest) =>
    boardRequest<CommentItem>(comments(postId), 'required', 'POST', body),
  updateComment: (postId: number, commentId: number, body: UpdateCommentRequest) =>
    boardRequest<CommentItem>(`${comments(postId)}/${commentId}`, 'required', 'PUT', body),
  deleteComment: (postId: number, commentId: number, version: number) =>
    boardRequest<void>(withQuery(`${comments(postId)}/${commentId}`, { version }), 'required', 'DELETE'),
  like: (postId: number, liked: boolean) => boardRequest<void>(`${post(postId)}/like`, 'required', liked ? 'PUT' : 'DELETE'),
  report: (body: CreateReportRequest) => boardRequest<ReportCreated>('/board/reports', 'required', 'POST', body),
};
