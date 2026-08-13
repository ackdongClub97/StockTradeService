// 부하테스트 공통 설정. 커맨드라인에서 k6 run -e KEY=VALUE 로 덮어쓸 수 있다.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const USER_COUNT = Number(__ENV.USER_COUNT || 500);        // 일반 유저 VU 수
export const FDS_ABUSER_COUNT = Number(__ENV.FDS_ABUSER_COUNT || 6); // FDS 룰 검증 전용 VU 수

export const USER_PREFIX = __ENV.USER_PREFIX || 'loadtest';
export const FDS_PREFIX = __ENV.FDS_PREFIX || 'fdsabuser';
export const PASSWORD = 'loadtest1234!';

// 매도 시나리오를 낼 수 있도록 사전에 보유수량을 만들어둘 일반 유저 비율
export const SELLER_RATIO = Number(__ENV.SELLER_RATIO || 0.3);

// KisWebSocketService.MAX_SUBSCRIPTIONS(40) 아래로 못박아서, 상세조회 대상 종목이
// 실시간 구독 캐시를 벗어나 매번 KIS REST 폴백을 타는 상황을 피한다.
export const STOCK_POOL_SIZE = Number(__ENV.STOCK_POOL_SIZE || 20);

// 액션 가중치 - 합이 1이 되도록 유지
export const WEIGHTS = {
  rank: 0.40,       // 랭킹 조회
  detail: 0.30,     // 단일 종목 상세 조회
  buyLimit: 0.12,   // 매수 - 지정가(자동 가격개선 포함)
  buyMarket: 0.08,  // 매수 - 현재가
  sell: 0.10,       // 매도
};

// 장 마감 이후처럼 실제 체결 검증이 의미 없는 시간대에 접속/조회 부하만 보고 싶을 때 true로 실행
// (k6 run -e READ_ONLY=true ...). true면 매수/매도/FDS 어뷰저 시나리오를 통째로 건너뛴다.
export const READ_ONLY = (__ENV.READ_ONLY || 'false') === 'true';

export const READ_ONLY_WEIGHTS = {
  rank: 0.5,
  detail: 0.5,
};
