import { PopularBoardPosts } from '../components/BoardEntry';
import { Link } from 'react-router-dom';
import { useMock } from '../api/client';
import { RankingPreview } from '../components/Rankings';
export default function Home() {
  const saved = localStorage.getItem(useMock ? 'sss-last-mock' : 'sss-last-real');
  return (
    <>
      <section className="hero">
        <h1>서울 골목에 가게 하나 차려 보기</h1>
        <p>서울시 상권 1,650곳의 실제 매출·점포·유동인구로 분기마다 결산합니다.</p>
        <Link className="button" to="/new">창업 시작</Link>
        {saved && (
          <Link className="continue" to={`/games/${saved}`}>
            이어서 하기
          </Link>
        )}

      </section>
      <section className="grid home-columns">
        <RankingPreview />

        <PopularBoardPosts />
      </section>
      <p><small>출처: 서울 열린데이터광장 상권분석서비스 · 공공데이터포털 소상공인 상가정보. 목 모드에서는 연습 데이터를 사용합니다.</small></p>
    </>
  );
}
