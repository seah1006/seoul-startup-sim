/* eslint-disable @typescript-eslint/no-explicit-any */
// SDK is loaded at runtime; keep its untyped boundary isolated in this component.
import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { Area, Stores } from '../api/types';
import { geometryToRings, toKakaoPath } from '../geo';
import { formatDistance } from './ResultCard';
declare global {
  interface Window {
    kakao: any;
  }
}
let sdk: Promise<void> | undefined;
function loadSdk(key: string) {
  if (!sdk)
    sdk = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;
      script.onload = () => window.kakao.maps.load(resolve);
      script.onerror = () =>
        reject(new Error('지도를 불러오지 못했습니다. 도메인 등록과 네트워크를 확인해 주세요.'));
      document.head.append(script);
    });
  return sdk;
}
// 고른 상권만 빨간 면으로 강조하고, 나머지 상권은 회색 경계선만 그린다(지도 전체가 붉게 덮이지 않게).
function areaStyle(selected: boolean) {
  const sign = getComputedStyle(document.documentElement).getPropertyValue('--sign').trim();
  return selected
    ? { strokeColor: sign, fillColor: sign, strokeWeight: 3, strokeOpacity: 1, fillOpacity: 0.16 }
    : {
        strokeColor: '#5b5a55',
        fillColor: sign,
        strokeWeight: 1,
        strokeOpacity: 0.55,
        fillOpacity: 0,
      };
}
export function AreaMap({
  area,
  position,
  stores,
  onArea,
  onPosition,
}: {
  area: Area | null;
  position: { lng: number; lat: number } | null;
  stores: Stores | null;
  onArea: (a: Area) => void;
  onPosition: (p: { lng: number; lat: number }) => void;
}) {
  const div = useRef<HTMLDivElement>(null);
  const map = useRef<any>(null);
  const callbacks = useRef({ onArea, onPosition });
  callbacks.current = { onArea, onPosition };
  const selected = useRef(area);
  selected.current = area;
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [truncated, setTruncated] = useState(false);
  const polygons = useRef<{ code: string; overlay: any }[]>([]);
  useEffect(() => {
    let disposed = false;
    const key = import.meta.env.VITE_KAKAO_JS_KEY;
    if (!key) {
      setError('지도 키가 없어 연습 상권의 중심점으로 위치를 선택할 수 있어요.');
      return;
    }
    let overlays: any[] = [];
    let listener: () => void;
    let timer: ReturnType<typeof setTimeout>;
    let generation = 0;
    loadSdk(key)
      .then(() => {
        if (disposed || !div.current) return;
        const k = window.kakao.maps;
        map.current = new k.Map(div.current, { center: new k.LatLng(37.556, 126.905), level: 5 });
        const render = async () => {
          const requestId = ++generation;
          const bounds = map.current.getBounds();
          const sw = bounds.getSouthWest(),
            ne = bounds.getNorthEast();
          try {
            const data = await api.areas(
              `${sw.getLng()},${sw.getLat()},${ne.getLng()},${ne.getLat()}`,
              15,
            );
            if (disposed || requestId !== generation) return;
            setError('');
            setTruncated(data.truncated);
            polygons.current = [];
            overlays.forEach((o) => o.setMap(null));
            overlays = [];
            for (const f of data.features) {
              for (const rings of geometryToRings(f.geometry)) {
                const polygon = new k.Polygon({
                  map: map.current,
                  path: toKakaoPath(rings, (lat, lng) => new k.LatLng(lat, lng)),
                  ...areaStyle(selected.current?.areaCode === f.properties.areaCode),
                });
                k.event.addListener(polygon, 'click', (e: any) => {
                  callbacks.current.onArea({
                    ...f.properties,
                    centroid: { lng: e.latLng.getLng(), lat: e.latLng.getLat() },
                  });
                  callbacks.current.onPosition({ lng: e.latLng.getLng(), lat: e.latLng.getLat() });
                });
                overlays.push(polygon);
                polygons.current.push({ code: f.properties.areaCode, overlay: polygon });
              }
            }
          } catch {
            setError('상권을 불러오지 못했습니다. 검색으로 다시 시도해 주세요.');
          }
        };
        listener = () => {
          clearTimeout(timer);
          timer = setTimeout(() => void render(), 250);
        };
        k.event.addListener(map.current, 'idle', listener);
        k.event.addListener(map.current, 'click', (e: any) => {
          if (selected.current)
            callbacks.current.onPosition({ lng: e.latLng.getLng(), lat: e.latLng.getLat() });
        });
        void render();
        setReady(true);
      })
      .catch((e) => setError(e.message));
    return () => {
      disposed = true;
      generation++;
      clearTimeout(timer);
      overlays.forEach((o) => o.setMap(null));
      if (map.current && listener)
        window.kakao.maps.event.removeListener(map.current, 'idle', listener);
    };
  }, []);
  useEffect(() => {
    for (const p of polygons.current)
      p.overlay.setOptions({
        ...areaStyle(p.code === area?.areaCode),
      });
    if (ready && area)
      map.current.panTo(new window.kakao.maps.LatLng(area.centroid.lat, area.centroid.lng));
  }, [ready, area]);
  useEffect(() => {
    if (!ready) return;
    const sign = getComputedStyle(document.documentElement).getPropertyValue('--sign').trim();
    const k = window.kakao.maps;
    const markers: any[] = [];
    if (position) {
      markers.push(
        new k.Marker({
          map: map.current,
          position: new k.LatLng(position.lat, position.lng),
          title: '내 가게',
        }),
      );
      markers.push(
        new k.Circle({
          map: map.current,
          center: new k.LatLng(position.lat, position.lng),
          radius: 300,
          strokeColor: sign,
          fillOpacity: 0.08,
          fillColor: sign,
        }),
      );
    }
    for (const store of stores?.stores || [])
      markers.push(
        new k.Marker({
          map: map.current,
          position: new k.LatLng(store.lat, store.lng),
          title: `${store.name} · ${formatDistance(store.distanceM)}`,
        }),
      );
    return () => markers.forEach((m) => m.setMap(null));
  }, [ready, position, stores]);
  return (
    <div className="map-wrap">
      <div className="map" ref={div} aria-label="상권 지도" />
      {truncated && <p className="notice">상권이 많아 일부만 표시했어요. 지도를 확대해 주세요.</p>}
      {error && <p className="map-message">{error}</p>}
      <small>상권 영역을 고른 뒤 지도에서 가게 위치를 찍으세요. 원은 반경 300m입니다.</small>
    </div>
  );
}
