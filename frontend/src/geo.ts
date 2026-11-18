export interface LatLngLiteral {
  lat: number;
  lng: number;
}
type Position = number[];
type Ring = Position[];
export type GeoJsonGeometry =
  | { type: 'Polygon'; coordinates: Ring[] }
  | { type: 'MultiPolygon'; coordinates: Ring[][] };

/**
 * GeoJSON 좌표는 [lng, lat] 순서다. 폴리곤마다 [바깥 링, 구멍 링...] 배열로 바꿔 돌려준다.
 * 카카오 Polygon은 링이 하나면 path를 평평한 배열로, 구멍이 있으면 배열의 배열로 받는다.
 */
export function geometryToRings(geometry: {
  type: string;
  coordinates: unknown;
}): LatLngLiteral[][][] {
  const polygons =
    geometry.type === 'Polygon'
      ? [geometry.coordinates as Ring[]]
      : geometry.type === 'MultiPolygon'
        ? (geometry.coordinates as Ring[][])
        : [];
  return polygons
    .map((rings) =>
      rings
        .map((ring) =>
          ring
            .filter((p) => p.length >= 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]))
            .map(([lng, lat]) => ({ lat, lng })),
        )
        .filter((ring) => ring.length >= 3),
    )
    .filter((rings) => rings.length > 0);
}

export function toKakaoPath<T>(rings: LatLngLiteral[][], make: (lat: number, lng: number) => T) {
  const paths = rings.map((ring) => ring.map((p) => make(p.lat, p.lng)));
  return paths.length === 1 ? paths[0] : paths;
}
