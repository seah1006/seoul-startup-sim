import BoardList from './components/BoardList';
import BoardDetail from './components/BoardDetail';
import { BoardEdit, BoardWrite } from './components/BoardEditor';
import { useEffect } from 'react';
import { BrowserRouter, Link, Route, Routes, useLocation } from 'react-router-dom';
import { api, useMock } from './api/client';
import { RankingsScreen } from './components/Rankings';
import Home from './pages/Home';
import NewOwner from './pages/NewOwner';
import Game from './pages/GamePage';
function AppShell() {
  const { pathname } = useLocation();
  const boardPath = /^\/board(?:\/|$)/i.test(pathname);
  useEffect(() => {
    if (!boardPath) void api.guest().catch(() => {});
  }, [boardPath]);
  return (
    <>
      <header>
        <Link className="brand" to="/">
          서울 사장님<span>서울에서 시작하는 나의 가게</span>
        </Link>
        <nav className="header-links" aria-label="상단 메뉴">
          <Link to="/board">커뮤니티</Link>
          <span className="badge">{useMock ? '연습 모드 · 목 데이터' : '실제 데이터 모드'}</span>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/board" element={<BoardList />} />
          <Route path="/board/posts/:postId" element={<BoardDetail />} />
          <Route path="/board/write" element={<BoardWrite />} />
          <Route path="/board/posts/:postId/edit" element={<BoardEdit />} />
          <Route path="/board/me" element={<BoardList key="mine" mine />} />
          <Route path="/rankings" element={<RankingsScreen />} />
          <Route path="/new" element={<NewOwner />} />
          <Route path="/games/:id" element={<Game />} />
          <Route path="*" element={<Link to="/">홈으로 돌아가기</Link>} />
        </Routes>
      </main>
      <footer>서울 사장님 · 실제 데이터와 게임 속 시뮬레이션을 구분해 표시합니다.</footer>
    </>
  );
}
export default function App() {
  return (
    <BrowserRouter>
      <AppShell />
    </BrowserRouter>
  );
}
