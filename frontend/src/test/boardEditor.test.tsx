import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import { boardApi } from '../api/board';
import { api, ApiError } from '../api/client';
import { EndingBoardEntry, PopularBoardPosts } from '../components/BoardEntry';

import { authenticate, ownFixture } from './authFixture';
beforeEach(() => {
  localStorage.clear();
  authenticate();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
function open(url: string) {
  window.history.replaceState({}, '', url);
  return render(<App />);
}
function fill(title: string, content: string) {
  fireEvent.change(screen.getByLabelText(/제목/), { target: { value: title } });
  fireEvent.change(screen.getByLabelText(/본문/), { target: { value: content } });
}

test('일반 글 작성: RECORD 선택 없음, 공백 검증, 글자 수 및 등록 후 상세', async () => {
  open('/board/write');
  expect(within(await screen.findByLabelText('카테고리')).getAllByRole('option')).toHaveLength(3);
  expect(screen.queryByRole('option', { name: '엔딩 인증' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '글 등록' }));
  expect(screen.getByRole('alert')).toHaveTextContent('제목은 1~100자');
  fill('연습 글 제목', '연습 본문');
  expect(screen.getByText('7/100자')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '글 등록' }));
  expect(await screen.findByRole('heading', { name: '연습 글 제목' })).toBeInTheDocument();
});

test('편집 충돌은 최신 버전을 재조회하고 사용자가 다시 저장해야 한다', async () => {
  const post = await boardApi.createPost({ category: 'FREE', title: '원래 제목', content: '본문' });
  open(`/board/posts/${post.postId}/edit`);
  await screen.findByDisplayValue('원래 제목');
  await boardApi.updatePost(post.postId, {
    category: 'QNA',
    title: '다른 곳에서 저장',
    content: '다른 본문',
    version: post.version,
  });
  fill('덮어쓸 수 없는 제목', '덮어쓸 수 없는 본문');
  fireEvent.click(screen.getByRole('button', { name: '수정 저장' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 곳에서 수정');
  expect(screen.getByRole('button', { name: '수정 저장' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '최신 내용 불러오기' }));
  expect(await screen.findByDisplayValue('다른 곳에서 저장')).toBeInTheDocument();
  expect(screen.getByLabelText('카테고리')).toHaveValue('QNA');
  fill('새로 확인하고 저장', '최신 기준 본문');
  fireEvent.click(screen.getByRole('button', { name: '수정 저장' }));
  expect(await screen.findByRole('heading', { name: '새로 확인하고 저장' })).toBeInTheDocument();
});

test.each([['category=RECORD&gameId=104'], ['gameId=104']])(
  'RECORD URL %s에서 미리보기와 mock 등록',
  async (query) => {
    open(`/board/write?${query}`);
    expect(
      await screen.findByRole('region', { name: '엔딩 인증 카드 미리보기' }),
    ).toHaveTextContent('가상 별빛우체국 찻집');
    expect(screen.getByText(/카드 값과 이름이 공개됩니다/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '공유 카드 보기' })).not.toBeInTheDocument();
    fill('엔딩 기록', '운영 후기');
    fireEvent.click(screen.getByRole('button', { name: '글 등록' }));
    expect(await screen.findByRole('heading', { name: '엔딩 기록' })).toBeInTheDocument();
    expect(await boardApi.recordLink(104)).toMatchObject({ title: '엔딩 기록' });
  },
);

test('내 글의 숨김 상태는 링크가 없고 공개 목록/연결 조회에 드러나지 않는다', async () => {
  await boardApi.post(2);
  ownFixture([30]);
  open('/board/me?category=RECORD');
  expect(await screen.findByText('숨김')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /숨김 처리된 연습 인증글/ })).not.toBeInTheDocument();
  expect(await boardApi.recordLink(105)).toBeUndefined();
  expect((await boardApi.posts({ category: 'RECORD' })).content).toHaveLength(3);
  await expect(
    boardApi.createPost({ category: 'RECORD', gameId: 105, title: '중복', content: '내용' }),
  ).rejects.toMatchObject({ status: 409, code: 'RECORD_ALREADY_POSTED' });
});

test('저장 중 이중 제출 차단, 서버 400 메시지와 429 안내', async () => {
  let fail: (error: unknown) => void = () => {};
  const send = vi.spyOn(boardApi, 'createPost').mockImplementationOnce(
    () =>
      new Promise((_, reject) => {
        fail = reject;
      }),
  );
  open('/board/write');
  await screen.findByLabelText('카테고리');
  fill('글 제목', '글 내용');
  const submit = screen.getByRole('button', { name: '글 등록' });
  fireEvent.click(submit);
  expect(submit).toBeDisabled();
  fireEvent.submit(submit.closest('form')!);
  expect(send).toHaveBeenCalledTimes(1);
  fail(new ApiError(400, '서버의 입력 오류', 'VALIDATION_FAILED'));
  expect(await screen.findByRole('alert')).toHaveTextContent('서버의 입력 오류');
  send.mockRejectedValueOnce(new ApiError(429, 'rate limit', 'RATE_LIMITED'));
  fireEvent.click(submit);
  expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도');
});

test('홈 인기글은 인기순 3건을 요청하고 상단 메뉴가 게시판을 연다', async () => {
  const list = vi.spyOn(boardApi, 'posts');
  const home = open('/');
  expect(await screen.findByRole('heading', { name: '커뮤니티 인기글' })).toBeInTheDocument();
  const panel = screen.getByRole('heading', { name: '커뮤니티 인기글' }).closest('article')!;
  expect(await within(panel).findAllByRole('listitem')).toHaveLength(3);
  expect(list).toHaveBeenCalledWith({ sort: 'popular', size: 3 }, expect.any(AbortSignal));
  fireEvent.click(
    within(screen.getByRole('navigation', { name: '상단 메뉴' })).getByRole('link', {
      name: '커뮤니티',
    }),
  );
  expect(await screen.findByRole('heading', { name: '사장님 커뮤니티' })).toBeInTheDocument();
  await screen.findByRole('list', { name: '게시글 목록' });
  home.unmount();
});

test('엔딩: 작성 가능, 공개 연결, 숨김 및 요약 미지원 분기', async () => {
  await boardApi.post(2);
  ownFixture([30]);
  const entry = (id: number) =>
    render(
      <MemoryRouter>
        <EndingBoardEntry gameId={id} />
      </MemoryRouter>,
    );
  const ready = entry(104);
  expect(await screen.findByRole('link', { name: '엔딩 인증하기' })).toHaveAttribute(
    'href',
    '/board/write?category=RECORD&gameId=104',
  );
  ready.unmount();
  const published = entry(101);
  expect(await screen.findByRole('link', { name: '인증글 보기' })).toHaveAttribute(
    'href',
    '/board/posts/2',
  );
  published.unmount();
  const hidden = entry(105);
  expect(await screen.findByText(/이 게임의 인증글은 숨김 상태/)).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '엔딩 인증하기' })).not.toBeInTheDocument();
  hidden.unmount();
  vi.spyOn(api, 'summary').mockRejectedValueOnce(new ApiError(404, '아직 미지원'));
  entry(104);
  expect(await screen.findByText(/엔딩 인증을 지금 사용할 수 없습니다/)).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '엔딩 인증하기' })).not.toBeInTheDocument();
});

test('홈은 게시판 API가 실패해도 접근 링크를 유지한다', async () => {
  vi.spyOn(boardApi, 'posts').mockRejectedValueOnce(new Error('offline'));
  render(
    <MemoryRouter>
      <PopularBoardPosts />
    </MemoryRouter>,
  );
  expect(await screen.findByText('인기글을 불러오지 못했습니다.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '커뮤니티 둘러보기' })).toHaveAttribute('href', '/board');
});

test('실제 목 게임 마지막 분기 결과에서 인증하기로 이어지고 PLAYING에는 노출되지 않는다', async () => {
  const game = await api.create({
    ownerName: '가상 사장',
    background: 'OFFICE_WORKER',
    candidateId: 'c1',
    candidateSeed: 123,
    focusStats: [],
    startQuarter: '20262',
  });
  await api.shop(game.gameId, {
    areaCode: 'demo-1',
    serviceCode: 'CS100010',
    lng: 126.9,
    lat: 37.5,
    shopName: '가상 은하정류장',
  });
  open(`/games/${game.gameId}`);
  await screen.findByRole('heading', { name: '가상 은하정류장' });
  expect(screen.queryByRole('link', { name: '엔딩 인증하기' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /다음 분기/ }));
  fireEvent.click(await screen.findByRole('link', { name: '엔딩 인증하기' }));
  expect(await screen.findByRole('region', { name: '엔딩 인증 카드 미리보기' })).toHaveTextContent(
    '가상 은하정류장',
  );
});

test('게스트 작성 진입은 폼 대신 로그인·가입 동선을 제공하고 원래 주소를 보존한다', async () => {
  localStorage.clear();
  await api.guest();
  open('/board/write?category=RECORD&gameId=104');
  expect(await screen.findByRole('link', { name: '로그인하고 참여하기' })).toHaveAttribute(
    'href',
    '/login?next=%2Fboard%2Fwrite%3Fcategory%3DRECORD%26gameId%3D104',
  );
  expect(screen.queryByLabelText(/제목/)).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: '가입하고 기록 이어가기' })).toHaveAttribute(
    'href',
    '/signup?next=%2Fboard%2Fwrite%3Fcategory%3DRECORD%26gameId%3D104',
  );
});
test('게스트 엔딩은 인증 작성 대신 가입 후 같은 엔딩으로 돌아오는 링크를 제공한다', async () => {
  localStorage.clear();
  await api.guest();
  render(
    <MemoryRouter initialEntries={['/games/104']}>
      <EndingBoardEntry gameId={104} />
    </MemoryRouter>,
  );
  expect(await screen.findByRole('link', { name: '가입하고 기록 이어가기' })).toHaveAttribute(
    'href',
    '/signup?next=%2Fgames%2F104',
  );
  expect(screen.queryByRole('link', { name: '엔딩 인증하기' })).not.toBeInTheDocument();
});
