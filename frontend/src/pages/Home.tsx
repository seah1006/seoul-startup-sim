import { PopularBoardPosts } from '../components/BoardEntry';
import { Link } from 'react-router-dom';
import { useMock } from '../api/client';
import { RankingPreview } from '../components/Rankings';
export default function Home() {
  const saved = localStorage.getItem(useMock ? 'sss-last-mock' : 'sss-last-real');
  return (
    <>
      <section className="hero">
        <p className="eyebrow">SEOUL STARTUP SIMULATOR</p>
        <h1>
          당신의 가게가
          <br />
          서울의 이야기가 되도록.
        </h1>
        <p>실제 서울 상권 데이터를 읽고, 나만의 창업 커리어를 만들어 보세요.</p>
        <Link className="button" to="/new">
          새 창업 시작 <span>↗</span>
        </Link>
        {saved && (
          <Link className="continue" to={`/games/${saved}`}>
            이전 창업 이어하기 →
          </Link>
        )}
        <div className="hero-bottom">
          <span>01 사장님 만들기</span>
          <span>02 동네 찾기</span>
          <span>03 분기마다 성장하기</span>
        </div>
      </section>
      <section className="grid two">
        <RankingPreview />
        <article className="card">
          <p className="eyebrow">REAL DATA, YOUR STORY</p>
          <h2>현실의 동네, 나만의 선택</h2>
          <p>
            서울 열린데이터광장 · 서울시 상권분석서비스
            <br />
            공공데이터포털 · 소상공인 상가정보
          </p>
          <small>목 모드에서는 명확히 표시한 연습 데이터만 사용합니다.</small>
        </article>
        <PopularBoardPosts />
      </section>
    </>
  );
}
