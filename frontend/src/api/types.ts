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

// API 계약 4장: 커뮤니티 게시판 (관리자 Thymeleaf 화면 제외).
export const boardCategories = ['FREE', 'TIP', 'QNA', 'RECORD'] as const;
export const boardSorts = ['latest', 'popular', 'views', 'comments'] as const;
export const boardSearchTypes = ['title', 'content', 'titleContent', 'author'] as const;
export type PostCategory = typeof boardCategories[number];
export type PostSort = typeof boardSorts[number];
export type PostSearchType = typeof boardSearchTypes[number];
export type BoardErrorCode = 'VALIDATION_FAILED' | 'UNAUTHORIZED' | 'FORBIDDEN' | 'NOT_FOUND'
  | 'INTERNAL_ERROR' | 'RATE_LIMITED' | 'COMMENT_DEPTH_EXCEEDED' | 'POST_VERSION_CONFLICT'
  | 'COMMENT_VERSION_CONFLICT' | 'RECORD_GAME_NOT_ENDED' | 'RECORD_ALREADY_POSTED' | 'ALREADY_REPORTED';

export interface PageQuery { page?: number; size?: number }
export interface PageResponse<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface PostSearchQuery extends PageQuery {
  category?: PostCategory; sort?: PostSort; q?: string; searchType?: PostSearchType; serviceCode?: string; areaCode?: string;
}
export interface RecordSnapshot {
  gameId: number; endingType: NonNullable<GameState['endingType']>; serviceCode: string; serviceName: string;
  areaCode: string; areaName: string; survivedQuarters: number; totalProfit: number;
  shopName: string; ownerName: string; shareUrl: string;
}
export interface GameSummary extends Omit<RecordSnapshot, 'serviceCode' | 'areaCode'> {
  bestQuarterProfit: number; shopCount: number;
}
export interface PostListItem {
  postId: number; category: PostCategory; title: string; authorNickname: string; createdAt: string; updatedAt: string;
  viewCount: number; likeCount: number; commentCount: number; isPinned: boolean; record: RecordSnapshot | null;
}
export interface PostDetail extends PostListItem { content: string; version: number; likedByMe: boolean; isMine: boolean }
export interface MyPostListItem extends PostListItem { status: 'ACTIVE' | 'HIDDEN'; version: number }
export type CreatePostRequest = { title: string; content: string } & (
  { category: Exclude<PostCategory, 'RECORD'>; gameId?: never } | { category: 'RECORD'; gameId: number }
);
export interface UpdatePostRequest { category: PostCategory; title: string; content: string; version: number }
export interface RecordPostLink { postId: number; title: string; commentCount: number; postUrl: string }
export interface CommentItem {
  commentId: number; parentId: number | null; authorNickname: string | null; content: string; createdAt: string;
  updatedAt: string | null; version: number | null; replyCount: number; isMine: boolean; isPlaceholder: boolean;
}
export interface CreateCommentRequest { content: string; parentId?: number | null }
export interface UpdateCommentRequest { content: string; version: number }
export interface CreateReportRequest { targetType: 'POST' | 'COMMENT'; targetId: number; reason: 'SPAM' | 'ABUSE' | 'ADULT' | 'ETC'; detail?: string }
export interface ReportCreated { reportId: number; status: 'PENDING' }
