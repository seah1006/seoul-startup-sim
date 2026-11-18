import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { expect, it } from 'vitest';
import App from '../App';
import { guestStorageKey } from '../api/client';
import BoardList from '../components/BoardList';

function HistoryControls() {
  const navigate = useNavigate();
  const { search } = useLocation();
  return (
    <>
      <button onClick={() => navigate(-1)}>뒤로</button>
      <span data-testid="url">{search}</span>
    </>
  );
}

it('RECORD 필터를 URL에 저장하고 탭 이동 및 뒤로 가기에서 복원한다', async () => {
  render(
    <MemoryRouter initialEntries={['/board?category=RECORD&serviceCode=CS100010&sort=popular']}>
      <BoardList />
      <HistoryControls />
    </MemoryRouter>,
  );
  expect(screen.getByRole('link', { name: '엔딩 인증' })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByLabelText('정렬')).toHaveValue('popular');
  expect(screen.getByLabelText('업종 코드')).toHaveValue('CS100010');
  expect(
    within(await screen.findByRole('list', { name: '게시글 목록' })).getAllByRole('listitem'),
  ).toHaveLength(2);
  fireEvent.change(screen.getByLabelText('상권 코드'), { target: { value: '3110123' } });
  fireEvent.change(screen.getByLabelText('검색 종류'), { target: { value: 'title' } });
  fireEvent.change(screen.getByLabelText('검색어'), { target: { value: '가상 구름모래' } });
  fireEvent.click(screen.getByRole('button', { name: '검색' }));
  expect(screen.getByTestId('url')).toHaveTextContent('areaCode=3110123');
  expect(screen.getByTestId('url')).toHaveTextContent(
    'q=%EA%B0%80%EC%83%81+%EA%B5%AC%EB%A6%84%EB%AA%A8%EB%9E%98',
  );
  expect(await screen.findByRole('link', { name: /가상 구름모래/ })).toBeVisible();
  fireEvent.click(screen.getByRole('link', { name: '자유' }));
  expect(screen.queryByLabelText('업종 코드')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('상권 코드')).not.toBeInTheDocument();
  expect(screen.getByTestId('url')).not.toHaveTextContent(/areaCode|serviceCode/);
  expect(await screen.findByText('조건에 맞는 게시글이 없습니다.')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '뒤로' }));
  expect(screen.getByLabelText('상권 코드')).toHaveValue('3110123');
  expect(screen.getByLabelText('검색 종류')).toHaveValue('title');
  expect(screen.getByLabelText('검색어')).toHaveValue('가상 구름모래');
});

it('인기순에도 고정 글을 표시하고 페이지와 정렬을 URL에 반영한다', async () => {
  render(
    <MemoryRouter initialEntries={['/board?sort=popular']}>
      <BoardList />
      <HistoryControls />
    </MemoryRouter>,
  );
  const list = await screen.findByRole('list', { name: '게시글 목록' });
  const rows = within(list).getAllByRole('listitem');
  expect(rows).toHaveLength(20);
  expect(within(rows[0]).getByText('고정')).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  expect(screen.getByTestId('url')).toHaveTextContent('page=1');
  expect(await screen.findByText('2 / 2')).toBeVisible();
  expect(
    within(screen.getByRole('list', { name: '게시글 목록' })).getAllByRole('listitem'),
  ).toHaveLength(9);
  fireEvent.change(screen.getByLabelText('정렬'), { target: { value: 'views' } });
  expect(screen.getByTestId('url')).toHaveTextContent('sort=views');
  expect(screen.getByTestId('url')).not.toHaveTextContent('page=');
  expect(await screen.findByText('1 / 2')).toBeVisible();
});

it('앱에서 /board에 직접 진입해도 게스트를 자동 발급하지 않는다', async () => {
  window.history.replaceState({}, '', '/board');
  try {
    render(<App />);
    expect(await screen.findByRole('list', { name: '게시글 목록' })).toBeVisible();
    expect(localStorage.getItem(guestStorageKey)).toBeNull();
  } finally {
    window.history.replaceState({}, '', '/');
  }
});

it('범위 밖 페이지를 검색 결과 없음과 구분하고 첫 페이지로 복귀한다', async () => {
  render(
    <MemoryRouter initialEntries={['/board?page=99']}>
      <BoardList />
      <HistoryControls />
    </MemoryRouter>,
  );
  expect(await screen.findByText('이 페이지에는 글이 없습니다.')).toBeVisible();
  expect(screen.queryByText('조건에 맞는 게시글이 없습니다.')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '첫 페이지로' }));
  expect(await screen.findByRole('list', { name: '게시글 목록' })).toBeVisible();
  expect(screen.getByTestId('url')).not.toHaveTextContent('page=');
});
