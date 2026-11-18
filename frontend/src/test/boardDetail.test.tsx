import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { boardApi } from '../api/board';
import { ApiError, guestStorageKey } from '../api/client';
import BoardDetail from '../components/BoardDetail';
import BoardList from '../components/BoardList';

afterEach(() => vi.restoreAllMocks());
const authenticate = () => localStorage.setItem(guestStorageKey, 'mock-guest');
function open(postId = 2) {
  return render(<MemoryRouter initialEntries={[`/board/posts/${postId}`]}><Routes>
    <Route path="/board/posts/:postId" element={<BoardDetail/>}/><Route path="/board" element={<BoardList/>}/>
  </Routes></MemoryRouter>);
}

it('태그 문자열과 줄바꿈을 원문 그대로 표시하고 인증 카드에서 공유 페이지를 연결한다', async () => {
  const post = await boardApi.post(2);
  const content = '<script>문자열</script>\n두 번째 줄';
  vi.spyOn(boardApi, 'post').mockResolvedValue({ ...post, content });
  open();
  const body = await screen.findByTestId('post-content');
  expect(body.textContent).toBe(content);
  expect(body.querySelector('script')).toBeNull();
  expect(body).toHaveClass('board-plain-text');
  expect(screen.getByRole('link', { name: '공유 카드 보기' })).toHaveAttribute('href', '/share/101');
  expect(screen.getByText('14분기')).toBeVisible();
  expect(screen.queryByRole('link', { name: '수정' })).not.toBeInTheDocument();
});

it('좋아요는 즉시 반영하고 동시에 두 번 전송하지 않으며 실패하면 복구한다', async () => {
  let fail!: (error: unknown) => void;
  const request = vi.spyOn(boardApi, 'like').mockImplementationOnce(() => new Promise((_, reject) => { fail = reject; }));
  open();
  fireEvent.click(await screen.findByRole('button', { name: '좋아요 1' }));
  const pending = screen.getByRole('button', { name: '좋아요 2' });
  expect(pending).toHaveAttribute('aria-pressed', 'true');
  expect(pending).toBeDisabled();
  fireEvent.click(pending);
  expect(request).toHaveBeenCalledExactlyOnceWith(2, true);
  fail(new ApiError(500, '좋아요를 저장하지 못했습니다.'));
  expect(await screen.findByRole('alert')).toHaveTextContent('좋아요를 저장하지 못했습니다.');
  expect(screen.getByRole('button', { name: '좋아요 1' })).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(screen.getByRole('button', { name: '좋아요 1' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '좋아요 2' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '좋아요 2' }));
  expect(request).toHaveBeenLastCalledWith(2, false);
  await waitFor(() => expect(screen.getByRole('button', { name: '좋아요 1' })).toBeEnabled());
});

it('자리표시자는 원문과 작성 동작을 숨기며 대댓글에는 답글 버튼이 없다', async () => {
  open();
  const placeholder = (await screen.findByText('표시할 수 없는 댓글입니다.')).closest('article')!;
  expect(within(placeholder).getAllByRole('button')).toHaveLength(1);
  fireEvent.click(within(placeholder).getByRole('button', { name: '답글 1개 보기' }));
  const reply = await screen.findByRole('article', { name: '대댓글' });
  expect(reply).toHaveTextContent('저도 다음 분기에 시도해 보려고요.');
  expect(within(reply).queryByRole('button', { name: /답글/ })).not.toBeInTheDocument();
  expect(screen.queryByText('화면에 노출되지 않는 삭제 댓글')).not.toBeInTheDocument();
});

it('댓글 작성·수정·삭제 후 목록과 공개 댓글 수를 갱신한다', async () => {
  authenticate(); open();
  await screen.findByText('고정비 관리가 중요하다는 데 공감해요.');
  fireEvent.change(screen.getByLabelText('댓글 내용'), { target: { value: '새 댓글입니다.' } });
  fireEvent.click(screen.getByRole('button', { name: '댓글 등록' }));
  const row = (await screen.findByText('새 댓글입니다.', { selector: 'article p' })).closest('article')!;
  expect(screen.getByRole('heading', { name: '댓글 5' })).toBeVisible();
  fireEvent.click(within(row).getByRole('button', { name: '댓글 수정' }));
  fireEvent.change(within(row).getByLabelText('수정할 댓글'), { target: { value: '수정한 댓글입니다.' } });
  fireEvent.click(within(row).getByRole('button', { name: '수정 저장' }));
  const edited = (await screen.findByText('수정한 댓글입니다.', { selector: 'article p' })).closest('article')!;
  fireEvent.click(within(edited).getByRole('button', { name: '댓글 삭제' }));
  fireEvent.click(within(edited).getByRole('button', { name: '댓글 삭제 확인' }));
  await screen.findByText('고정비 관리가 중요하다는 데 공감해요.');
  expect(screen.queryByText('수정한 댓글입니다.')).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '댓글 4' })).toBeVisible();
});

it('댓글 수정 409에서 자동 재전송하지 않고 사용자가 최신 댓글을 다시 읽을 수 있다', async () => {
  authenticate(); open();
  const row = (await screen.findByText('고정비 관리가 중요하다는 데 공감해요.')).closest('article')!;
  await boardApi.updateComment(2, 2, { content: '다른 창의 최신 댓글', version: 0 });
  fireEvent.click(within(row).getByRole('button', { name: '댓글 수정' }));
  fireEvent.click(within(row).getByRole('button', { name: '수정 저장' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('댓글이 다른 곳에서 수정되었습니다.');
  fireEvent.click(screen.getByRole('button', { name: '최신 내용 불러오기' }));
  expect(await screen.findByText('다른 창의 최신 댓글')).toBeVisible();
});

it('댓글 작성 429에 재시도 안내를 보이고 입력을 유지한다', async () => {
  vi.spyOn(boardApi, 'createComment').mockRejectedValue(new ApiError(429, '제한 초과', 'RATE_LIMITED'));
  open();
  await screen.findByText('고정비 관리가 중요하다는 데 공감해요.');
  fireEvent.change(screen.getByLabelText('댓글 내용'), { target: { value: '유지할 내용' } });
  fireEvent.click(screen.getByRole('button', { name: '댓글 등록' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.');
  expect(screen.getByLabelText('댓글 내용')).toHaveValue('유지할 내용');
});

it('본인 글 삭제 후 목록으로 이동하고 삭제된 글이 사라진다', async () => {
  authenticate(); open();
  expect(await screen.findByRole('link', { name: '수정' })).toHaveAttribute('href', '/board/posts/2/edit');
  fireEvent.click(screen.getByRole('button', { name: '게시글 삭제' }));
  fireEvent.click(screen.getByRole('button', { name: '게시글 삭제 확인' }));
  const list = await screen.findByRole('list', { name: '게시글 목록' });
  expect(within(list).queryByRole('link', { name: /가상 구름모래/ })).not.toBeInTheDocument();
});

it('만료 토큰의 공개 조회는 유지하고 쓰기 401이면 게스트 시작을 안내한다', async () => {
  localStorage.setItem(guestStorageKey, 'expired'); open();
  fireEvent.click(await screen.findByRole('button', { name: '좋아요 1' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('게스트로 시작해 주세요.');
  expect(localStorage.getItem(guestStorageKey)).toBe('expired');
  fireEvent.click(screen.getByRole('button', { name: '게스트로 시작' }));
  await waitFor(() => expect(localStorage.getItem(guestStorageKey)).toBe('mock-guest'));
  expect(await screen.findByRole('link', { name: '수정' })).toBeVisible();
});

it('루트와 대댓글 페이지를 각각 조회한다', async () => {
  const [roots, replies] = await Promise.all([boardApi.comments(2), boardApi.replies(2, 1)]);
  const closure = roots.content[0];
  const rootRequest = vi.spyOn(boardApi, 'comments').mockImplementation(async (_, query) => ({
    ...roots, page: query?.page ?? 0, totalElements: 21, totalPages: 2,
    content: query?.page ? [{ ...closure, commentId: 100, content: '루트 두 번째 페이지' }] : roots.content,
  }));
  const replyRequest = vi.spyOn(boardApi, 'replies').mockImplementation(async (_, __, query) => ({
    ...replies, page: query?.page ?? 0, totalElements: 21, totalPages: 2,
    content: query?.page ? [{ ...replies.content[0], commentId: 101, content: '대댓글 두 번째 페이지' }] : replies.content,
  }));
  open();
  const row = (await screen.findByText(closure.content)).closest('article')!;
  fireEvent.click(within(row).getByRole('button', { name: '답글 1개 보기' }));
  fireEvent.click(within(await screen.findByRole('navigation', { name: '대댓글 페이지' })).getByRole('button', { name: '다음' }));
  expect(await screen.findByText('대댓글 두 번째 페이지')).toBeVisible();
  expect(replyRequest).toHaveBeenLastCalledWith(2, 1, { page: 1, size: 20 });
  fireEvent.click(within(screen.getByRole('navigation', { name: '댓글 페이지' })).getByRole('button', { name: '다음' }));
  expect(await screen.findByText('루트 두 번째 페이지')).toBeVisible();
  expect(rootRequest).toHaveBeenLastCalledWith(2, { page: 1, size: 20 });
  expect(screen.queryByText('대댓글 두 번째 페이지')).not.toBeInTheDocument();
});

it('신고 모달의 기타 사유는 설명을 요구하고 댓글 대상으로 제출한다', async () => {
  const prototype = HTMLDialogElement.prototype;
  const showModal = Object.getOwnPropertyDescriptor(prototype, 'showModal');
  const close = Object.getOwnPropertyDescriptor(prototype, 'close');
  Object.defineProperty(prototype, 'showModal', { configurable: true, value(this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(prototype, 'close', { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute('open'); } });
  const send = vi.spyOn(boardApi, 'report');
  const mounted = open();
  try {
    const row = (await screen.findByText('첫 분기의 운영 시간은 어떻게 정하셨나요?')).closest('article')!;
    fireEvent.click(within(row).getByRole('button', { name: '댓글 신고' }));
    const modal = screen.getByRole('dialog', { name: '댓글 신고' });
    fireEvent.change(within(modal).getByLabelText('신고 사유'), { target: { value: 'ETC' } });
    const submit = within(modal).getByRole('button', { name: '신고 접수' });
    expect(submit).toBeDisabled();
    fireEvent.change(within(modal).getByLabelText('상세 설명 (필수)'), { target: { value: '사유 설명' } });
    fireEvent.click(submit);
    expect(await screen.findByText('신고가 접수되었습니다.')).toBeVisible();
    expect(send).toHaveBeenCalledExactlyOnceWith({ targetType: 'COMMENT', targetId: 1, reason: 'ETC', detail: '사유 설명' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  } finally {
    mounted.unmount();
    if (showModal) Object.defineProperty(prototype, 'showModal', showModal); else Reflect.deleteProperty(prototype, 'showModal');
    if (close) Object.defineProperty(prototype, 'close', close); else Reflect.deleteProperty(prototype, 'close');
  }
});
