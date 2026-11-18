import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { geometryToRings, toKakaoPath } from '../geo';
import { formatDistance, money } from '../components/ResultCard';

const make = (lat: number, lng: number) => ({ lat, lng });
const ring = (o: number) => [
  [126.9 + o, 37.5],
  [126.91 + o, 37.5],
  [126.91 + o, 37.51],
  [126.9 + o, 37.5],
];

describe('GeoJSON to Kakao path', () => {
  it('Polygon: [lng, lat]를 lat/lng로 바꾸고 링 하나면 평평한 배열', () => {
    const rings = geometryToRings({ type: 'Polygon', coordinates: [ring(0)] });
    expect(rings).toHaveLength(1);
    const path = toKakaoPath(rings[0], make) as { lat: number; lng: number }[];
    expect(path[0]).toEqual({ lat: 37.5, lng: 126.9 });
    expect(path).toHaveLength(4);
  });
  it('구멍이 있으면 배열의 배열(바깥, 구멍)', () => {
    const rings = geometryToRings({ type: 'Polygon', coordinates: [ring(0), ring(0.001)] });
    const path = toKakaoPath(rings[0], make) as unknown[][];
    expect(path).toHaveLength(2);
    expect(Array.isArray(path[0])).toBe(true);
  });
  it('MultiPolygon: 폴리곤마다 따로 반환', () => {
    const rings = geometryToRings({
      type: 'MultiPolygon',
      coordinates: [[ring(0)], [ring(0.1), ring(0.101)]],
    });
    expect(rings).toHaveLength(2);
    expect(rings[1]).toHaveLength(2);
  });
  it('잘못된 좌표와 알 수 없는 타입은 무시', () => {
    expect(geometryToRings({ type: 'Point', coordinates: [1, 2] })).toEqual([]);
    expect(geometryToRings({ type: 'Polygon', coordinates: [[[1, 2]]] })).toEqual([]);
  });
});

describe('표기 형식', () => {
  it('거리는 정수 m, 1000m 이상은 km', () => {
    expect(formatDistance(30.22368283)).toBe('30m');
    expect(formatDistance(999.4)).toBe('999m');
    expect(formatDistance(1234)).toBe('1.2km');
  });
  it('money 규칙', () => {
    expect(money(446630000)).toBe('4억 4,663만 원');
    expect(money(33280000)).toBe('3,328만 원');
    expect(money(9800)).toBe('9,800원');
    expect(money(-9800)).toBe('-9,800원');
  });
});

describe('CSS 규칙', () => {
  const css = readFileSync('src/style.css', 'utf8');
  it('전역 svg 규칙이 없어 카카오맵 폴리곤/원 SVG를 깨지 않는다', () => {
    expect(css).not.toMatch(/^svg\s*\{/m);
  });
  it('검색 버튼은 줄바꿈하지 않는다', () => {
    expect(css).toMatch(/\.search button\s*\{[^}]*white-space:\s*nowrap/);
  });
  it('모바일에서 그리드 열이 내용 때문에 넘치지 않고 랭킹 표는 카드형이다', () => {
    expect(css).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\);/);
    expect(css).toMatch(/\.ranking-table \.rank-row\s*\{[^}]*display:\s*grid/);
    expect(css).toMatch(/\.table-scroll\s*\{[^}]*overflow-x:\s*auto/);
  });
});
