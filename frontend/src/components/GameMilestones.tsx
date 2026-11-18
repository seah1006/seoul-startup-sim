import {EndingBoardEntry} from './BoardEntry';
import {useEffect,useState} from 'react';
import {Link} from 'react-router-dom';
import {api,ApiError} from '../api/client';
import type {GameState,GameSummary,TurningPoint,TurningPointResult} from '../api/types';
import {money} from './ResultCard';
export function TurningPointCard({game,onGame}:{game:GameState;onGame:(g:GameState)=>void}){
 const [point,setPoint]=useState<TurningPoint|null>(null),[result,setResult]=useState<TurningPointResult|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{let active=true;api.turningPoint(game.gameId).then(p=>{if(active){setPoint(p);if(!p)setError('대기 중인 전환점이 없습니다. 게임을 다시 불러와 주세요.');}}).catch(e=>{if(active)setError(e.message);});return()=>{active=false;};},[game.gameId]);
 async function choose(id:number){setBusy(true);setError('');try{setResult(await api.choose(game.gameId,id));}catch(e){setError((e as Error).message);if((e as ApiError).status===409)onGame(await api.game(game.gameId));}finally{setBusy(false);}}
 return <section className="card turning-point" aria-label="전환점"><p className="eyebrow">TURNING POINT</p>{result?<><h2>{result.success?'선택이 좋은 결실을 맺었어요':'새로운 경험이 되었어요'}</h2><p>{result.success?'성공':'실패'} · {result.resultText}</p><div className="stat-chips">{result.effects.map(e=><span key={e.label}>{e.label} {e.value}</span>)}</div><button className="primary" onClick={()=>onGame(result.game)}>계속</button></>:<><h2>{point?.title||'전환점을 불러오는 중…'}</h2><p>{point?.description}</p><div className="grid two">{point?.options.map(o=><article className="card" key={o.optionId}><h3>{o.label}</h3><label>성공 확률 {o.successRatePct}%<meter min="0" max="100" value={o.successRatePct}/></label><p>성공: {o.onSuccess}</p><p>실패: {o.onFail}</p><button disabled={busy} onClick={()=>void choose(o.optionId)}>{busy?'선택 중…':o.label}</button></article>)}</div></>}{error&&<p role="alert" className="error">{error}</p>}</section>;
}
export function EndingScreen({game}:{game:GameState}){
 const [summary,setSummary]=useState<GameSummary|null>(null),[error,setError]=useState(''),[notice,setNotice]=useState('');
 useEffect(()=>{let active=true;api.summary(game.gameId).then(s=>{if(active)setSummary(s);}).catch(e=>{if(active)setError(e.message);});return()=>{active=false;};},[game.gameId]);
 async function share(){if(!summary)return;try{await navigator.clipboard.writeText(new URL(summary.shareUrl,window.location.origin).href);setNotice('공유 링크를 복사했어요.');}catch{setNotice('자동 복사에 실패했어요. 아래 공유 페이지 링크를 복사해 주세요.');}}
 const titles={CLOSED:'가게의 문을 닫고, 다음 시작을 준비해요',SOLD:'가게를 매각하고 은퇴했어요',LONG_RUNNING:'오래 사랑받는 가게가 되었어요'};
 return <section className="card ending"><p className="eyebrow">YOUR STORY</p><h2>{titles[game.endingType||'LONG_RUNNING']}</h2>{summary?<><p>{summary.ownerName} 사장님의 {summary.shopName}</p><p>{summary.areaName} · {summary.serviceName}</p><div className="metrics"><div>생존 기간<strong>{summary.survivedQuarters}분기</strong></div><div>누적 순이익<strong>{money(summary.totalProfit)}</strong></div><div>최고 분기 순이익<strong>{money(summary.bestQuarterProfit)}</strong></div></div><div className="choices"><button onClick={()=>void share()}>공유하기</button><a className="button" href={summary.shareUrl}>공유 페이지</a><Link className="button primary" to="/new">새 창업</Link></div>{notice&&<p role="status">{notice}</p>}<EndingBoardEntry key={game.gameId} gameId={game.gameId}/></>:<p role="status">{error||'창업 여정을 정리하고 있어요…'}</p>}</section>;
}
export function SellButton({game,onGame}:{game:GameState;onGame:(g:GameState)=>void}){
 const [confirm,setConfirm]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 async function sell(){setBusy(true);setError('');try{onGame(await api.sell(game.gameId,game.version));}catch(e){setError((e as Error).message);if((e as ApiError).status===409){onGame(await api.game(game.gameId));setConfirm(false);}}finally{setBusy(false);}}
 return <section><button disabled={game.currentTurn<4} onClick={()=>setConfirm(true)}>가게 매각하고 은퇴</button>{game.currentTurn<4&&<p><small>4분기 이상 운영한 뒤 매각할 수 있어요.</small></p>}{confirm&&<div className="card" role="dialog" aria-modal="true" aria-labelledby="sell-title"><h3 id="sell-title">가게를 매각하고 은퇴할까요?</h3><p>매각하면 이번 창업을 종료하며 되돌릴 수 없습니다.</p><div className="choices"><button disabled={busy} onClick={()=>setConfirm(false)}>취소</button><button className="primary" disabled={busy} onClick={()=>void sell()}>{busy?'매각 중…':'매각 확정'}</button></div></div>}{error&&<p role="alert" className="error">{error}</p>}</section>;
}
