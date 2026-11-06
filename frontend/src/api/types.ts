export type Stat = 'cooking'|'service'|'management'|'marketing'|'stamina'|'network';
export type Stats = Record<Stat,number>;
export type Background = 'OFFICE_WORKER'|'CULINARY'|'PART_TIMER'|'LOCAL';
export interface Candidate { candidateId:string; stats:Stats; total:number }
export interface OwnerInput { ownerName:string; background:Background; candidateId:string; candidateSeed:number; focusStats:Stat[]; startQuarter?:string }
export interface ShopInput { areaCode:string; serviceCode:string; lng:number; lat:number; shopName:string }
export interface Decision { priceLevel:'LOW'|'NORMAL'|'HIGH'; openHours:number; staff:number; marketingBudget:number; ownerActivity:'MENU_DEV'|'REGULARS'|'STUDY'|'REST' }
export interface GameState { gameId:number; version:number; status:'PLAYING'|'ENDED'; endingType:'CLOSED'|'SOLD'|'LONG_RUNNING'|null; currentTurn:number; currentQuarter:string; startQuarter:string; lastQuarter:string; cash:number; owner:{name:string; background:Background; stats:Stats; focusStats:Stat[]}; shop:null|(ShopInput & {areaName:string; serviceName:string; rivalCount:number}); pendingDecision:boolean }
export interface QuarterResult { turn:number; quarter:string; headline:{label:string;amount:number}; revenue:number; visitors:number; avgTicket:number; costs:{rent:number;ingredients:number;labor:number;marketing:number;total:number}; profit:number; cashAfter:number; areaRank:{rank:number;of:number}; statChanges:{stat:Stat;before:number;after:number}[]; areaNews:{openings:number;closings:number;source:string}; moment:unknown|null; reactions:unknown[]; dataNotes:string[] }
export interface Industry { serviceCode:string;name:string;category:string;playable:boolean }
export interface Area { areaCode:string;name:string;areaTypeName:string;districtName:string;centroid:{lng:number;lat:number};dongName?:string;areaM2?:number }
export interface AreaFeature {type:'Feature';properties:Omit<Area,'centroid'>;geometry:{type:'Polygon'|'MultiPolygon';coordinates:number[][][]|number[][][][]}}
export interface AreaCollection {type:'FeatureCollection';features:AreaFeature[];truncated:boolean}
export interface Report {areaCode:string;name:string;quarter:string;floatingPopulation:number;industries:(Industry & {storeCount:number;franchiseCount:number;openingRatePct:number;closingRatePct:number;quarterSales:number|null;salesEstimated:boolean})[]}
export interface Stores {count:number;stores:{storeId:string;name:string;lng:number;lat:number;distanceM:number}[]}
export const statLabels:Record<Stat,string> = {cooking:'요리',service:'서비스',management:'경영',marketing:'마케팅',stamina:'체력',network:'인맥'};
export const backgrounds:Record<Background,string> = {OFFICE_WORKER:'전직 직장인',CULINARY:'요리학교 출신',PART_TIMER:'알바 10년차',LOCAL:'동네 토박이'};
