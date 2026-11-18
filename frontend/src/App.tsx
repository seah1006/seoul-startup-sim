import BoardList from './components/BoardList';
import BoardDetail from './components/BoardDetail';
import { BoardEdit, BoardWrite } from './components/BoardEditor';
import { AuthHeader, Login, MyAccount, MemberOnly } from './components/Auth';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import { useMock } from './api/client';
import { RankingsScreen } from './components/Rankings';
import Home from './pages/Home';
import NewOwner from './pages/NewOwner';
import Game from './pages/GamePage';
function AppShell() {
  return (
    <>
      <header>
        <Link className="brand" to="/">
          서울 사장님<span>서울에서 시작하는 나의 가게</span>
        </Link>
        <nav className="header-links" aria-label="상단 메뉴">
          <Link to="/board">커뮤니티</Link>
          <AuthHeader />
          <span className="badge">{useMock ? '연습 모드 · 목 데이터' : '실제 데이터 모드'}</span>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/login" element={<Login key="login" />} />
          <Route path="/signup" element={<Login key="signup" signup />} />
          <Route path="/me" element={<MyAccount />} />
          <Route path="/" element={<Home />} />
          <Route path="/board" element={<BoardList />} />
          <Route path="/board/posts/:postId" element={<BoardDetail />} />
          <Route
            path="/board/write"
            element={
              <MemberOnly>
                <BoardWrite />
              </MemberOnly>
            }
          />
          <Route
            path="/board/posts/:postId/edit"
            element={
              <MemberOnly>
                <BoardEdit />
              </MemberOnly>
            }
          />
          <Route
            path="/board/me"
            element={
              <MemberOnly>
                <BoardList key="mine" mine />
              </MemberOnly>
            }
          />
          <Route path="/rankings" element={<RankingsScreen />} />
          <Route path="/new" element={<NewOwner />} />
          <Route path="/games/:id" element={<Game />} />
          <Route
            path="*"
            element={
              <section>
                <h1>페이지를 찾을 수 없어요</h1>
                <p>주소를 확인하거나 홈에서 다시 시작해 주세요.</p>
                <Link to="/">홈으로 돌아가기</Link>
              </section>
            }
          />
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
