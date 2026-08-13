import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import {
  BASE_URL, USER_COUNT, FDS_ABUSER_COUNT, USER_PREFIX, FDS_PREFIX,
  PASSWORD, SELLER_RATIO, STOCK_POOL_SIZE, WEIGHTS, READ_ONLY, READ_ONLY_WEIGHTS,
} from './config.js';
import { login } from './lib/auth.js';

const rankDuration = new Trend('rank_duration', true);
const detailDuration = new Trend('detail_duration', true);
const orderDuration = new Trend('order_duration', true);
const orderSuccessRate = new Rate('order_success_rate');
const fdsOrdersSent = new Counter('fds_triggering_orders_sent');

// VU(iteration 0)가 로그인해서 받은 JSESSIONID를 담아두는 모듈 스코프 변수.
// k6는 iteration 사이에 자동 쿠키 저장소를 비우므로(auth.js 주석 참고), 이 값을 직접
// Cookie 헤더로 매 요청에 붙여야 세션이 유지된다. 모듈 top-level 변수는 VU마다 독립된
// JS 실행 컨텍스트를 가지므로 다른 VU와 섞이지 않는다.
let sessionCookie = null;

// READ_ONLY(장 마감 후 등 실제 체결 검증이 무의미한 시간대)일 땐 주문을 아예 안 내므로
// FDS 어뷰저 시나리오(전부 주문 기반)와 주문 관련 임계치를 통째로 뺀다.
export const options = {
  scenarios: READ_ONLY ? {
    // 상황1(읽기 전용) - 랭킹 조회 / 상세 조회만
    normal_users: {
      executor: 'ramping-vus',
      exec: 'normalUser',
      startVUs: 0,
      stages: [
        { duration: '2m', target: USER_COUNT },
        { duration: '6m', target: USER_COUNT },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  } : {
    // 상황1 - 랭킹 조회 / 상세 조회 / 매수(지정가·현재가) / 매도를 섞은 일반 유저 500명 동시접속
    normal_users: {
      executor: 'ramping-vus',
      exec: 'normalUser',
      startVUs: 0,
      stages: [
        { duration: '2m', target: USER_COUNT }, // 램프업
        { duration: '6m', target: USER_COUNT }, // 유지
        { duration: '1m', target: 0 },          // 램프다운
      ],
      gracefulRampDown: '30s',
    },
    // 상황3 - FDS 3개 룰(주문빈도/가격이상치/왕복매매)을 의도적으로 건드리는 소수 VU.
    // 일반 유저 램프업이 끝난 뒤 시작해서, 그 부하 위에 얹혔을 때도 정상 탐지되는지 본다.
    fds_abusers: {
      executor: 'constant-vus',
      exec: 'fdsAbuser',
      vus: FDS_ABUSER_COUNT,
      duration: '6m',
      startTime: '2m',
    },
  },
  setupTimeout: '15m',
  thresholds: READ_ONLY ? {
    rank_duration: ['p(95)<500'],
    detail_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  } : {
    rank_duration: ['p(95)<500'],
    detail_duration: ['p(95)<500'],
    order_duration: ['p(95)<1500'],
    http_req_failed: ['rate<0.01'],
    // 계정당 시드머니(1천만원)가 고정이라, 9분짜리 테스트 하나 동안 매수가 계속 나가면 뒤로 갈수록
    // 잔고 부족(400, 정상 거절)이 늘어나는 게 정상 - 실제로 잔고를 전부 리셋하고 재실행해도
    // 성공률이 시작 시점 ~70%에서 종료 시점 ~30%대로 자연스럽게 떨어짐(issue.md 2026-08-12/13 기록 참고).
    // 0.95는 이 구조를 고려 안 한 비현실적인 값이었어서, 깨끗한(302 버그 수정 후) 기준 실행 결과에 맞춰 낮춤.
    order_success_rate: ['rate>0.5'],
  },
};

/* ---------------- setup: 1회만 실행 ---------------- */
export function setup() {
  const sellerCount = Math.floor(USER_COUNT * SELLER_RATIO);

  if (READ_ONLY) {
    console.log(`[setup] READ_ONLY 모드 - 일반 유저 ${USER_COUNT}명만 가입(FDS 어뷰저/보유수량 시딩 생략, 랭킹·상세조회만 부하테스트)`);
  } else {
    console.log(`[setup] 일반 유저 ${USER_COUNT}명 가입 (매도 시나리오용 시드 보유 ${sellerCount}명), FDS 어뷰저 ${FDS_ABUSER_COUNT}명 가입`);
  }

  const users = [];
  for (let i = 1; i <= USER_COUNT; i++) {
    users.push({ memberId: `${USER_PREFIX}_${i}`, isSeller: i <= sellerCount });
  }
  const abusers = [];
  if (!READ_ONLY) {
    for (let i = 1; i <= FDS_ABUSER_COUNT; i++) {
      abusers.push({ memberId: `${FDS_PREFIX}_${i}` });
    }
  }

  // 회원가입은 세션이 필요 없는 permitAll API라 batch로 병렬 처리
  const joinReqs = [];
  users.forEach((u, i) => joinReqs.push(joinPayload(u.memberId, phoneFor(i + 1, 'u'))));
  abusers.forEach((a, i) => joinReqs.push(joinPayload(a.memberId, phoneFor(i + 1, 'f'))));

  const BATCH_SIZE = 50;
  let failed = 0;
  for (let i = 0; i < joinReqs.length; i += BATCH_SIZE) {
    const batch = joinReqs.slice(i, i + BATCH_SIZE);
    const responses = http.batch(batch);
    responses.forEach((r) => { if (r.status !== 200) failed++; });
  }
  if (failed > 0) console.warn(`[setup] 회원가입 실패 ${failed}건 (이미 가입되어 있다면 무시 가능 - H2 데이터가 이전 실행에서 남아있는 경우)`);

  // 종목 풀 확보 - 실제 랭킹 캐시에서 가져온다(장이 열려 있어야 데이터가 참, 장 마감 후엔 마감 시점 캐시를 그대로 서빙).
  const stockPool = fetchStockPool();
  if (stockPool.length === 0) {
    throw new Error('[setup] 랭킹 캐시가 비어 있음 - 장 시간(09:00~15:30 KST)에 앱을 켜둔 상태에서 테스트를 실행해야 함');
  }
  console.log(`[setup] 종목 풀 ${stockPool.length}개: ${stockPool.map((s) => s.code).join(',')}`);

  if (READ_ONLY) {
    return { stockPool, users, abusers };
  }

  // 매도 시나리오/왕복매매 FDS 시나리오가 쓸 보유수량을 미리 만들어둔다.
  // MARKET 매수는 tryMatch로 사실상 즉시 체결되므로 실행 부담이 크지 않다.
  // (장이 닫혀있으면 tryMatch가 체결을 안 하므로 이 시딩 자체가 무의미 - READ_ONLY가 아닐 때만 돈다)
  const seedTargets = users.filter((u) => u.isSeller).concat(abusers);
  // 시드머니(1천만원) 대비 종목 단가를 고려해서 수량을 정한다 - 50주로 고정해뒀다가 고가주가 뽑히면
  // (예: 24만원대 종목 50주=1,240만원) 시딩 자체가 잔고부족으로 실패하는 사고가 있었음.
  const seedQty = Math.max(1, Math.min(50, Math.floor(9000000 / stockPool[0].price)));
  console.log(`[setup] 보유수량 시딩 대상 ${seedTargets.length}명 (종목 ${stockPool[0].code}, 1인당 ${seedQty}주)`);
  let seeded = 0;
  seedTargets.forEach((t) => {
    const cookie = login(t.memberId, PASSWORD);
    const res = submitOrder({
      stockCode: stockPool[0].code,
      stockName: stockPool[0].name,
      orderType: 'BUY',
      priceMode: 'MARKET',
      price: stockPool[0].price,
      quantity: seedQty,
    }, cookie);
    if (res.status === 200) seeded++;
  });
  sleep(3); // Kafka 컨슈머 + tryMatch가 처리될 시간을 한 번에 벌어준다
  console.log(`[setup] 보유수량 시딩 주문 제출 완료 ${seeded}/${seedTargets.length}건 (실제 체결 여부는 본 테스트의 매도 액션에서 자연스럽게 드러남)`);

  return { stockPool, users, abusers };
}

function phoneFor(seq, tag) {
  return `010${tag === 'u' ? '9' : '8'}${String(seq).padStart(7, '0')}`;
}

function joinPayload(memberId, phone) {
  return {
    method: 'POST',
    url: `${BASE_URL}/api/member/join`,
    body: JSON.stringify({
      memberId, password: PASSWORD, name: memberId, phone, email: `${memberId}@loadtest.local`,
    }),
    // 이전 실행에서 만든 loadtest_* 계정이 H2에 남아있는 채로 재실행하면 "이미 사용 중인 아이디" 400이 정상적으로 뜬다.
    // 이건 재실행 시의 예상된 응답이라 http_req_failed에 안 잡히게 한다(진짜 5xx/네트워크 오류만 실패로 카운트).
    params: {
      headers: { 'Content-Type': 'application/json' },
      responseCallback: http.expectedStatuses(200, 400),
    },
  };
}

function fetchStockPool() {
  const res = http.get(`${BASE_URL}/api/volume/cached`);
  if (res.status !== 200) return [];
  const body = res.json();
  return (body.data || []).slice(0, STOCK_POOL_SIZE).map((d) => ({
    code: d.mkscShrnIscd,
    name: d.htsKorIsnm,
    price: parseInt(d.stckPrpr, 10) || 0,
  })).filter((s) => s.code && s.price > 0);
}

/* ---------------- 상황1: 일반 유저 500명 ---------------- */
export function normalUser(data) {
  const u = data.users[(__VU - 1) % data.users.length];
  if (__ITER === 0) sessionCookie = login(u.memberId, PASSWORD);

  const r = Math.random();
  let acc = 0;
  if (READ_ONLY) {
    // 장 마감 등으로 체결 검증이 무의미할 때 - 랭킹/상세조회만, 주문은 아예 안 냄
    if (r < (acc += READ_ONLY_WEIGHTS.rank)) {
      doRank();
    } else {
      doDetail(data.stockPool);
    }
  } else if (r < (acc += WEIGHTS.rank)) {
    doRank();
  } else if (r < (acc += WEIGHTS.detail)) {
    doDetail(data.stockPool);
  } else if (r < (acc += WEIGHTS.buyLimit)) {
    doBuy(data.stockPool, 'LIMIT');
  } else if (r < (acc += WEIGHTS.buyMarket)) {
    doBuy(data.stockPool, 'MARKET');
  } else {
    doSell(data.stockPool, u.isSeller);
  }

  sleep(1 + Math.random() * 2); // 1~3초 think-time - 실제 사용자 행동을 흉내
}

function doRank() {
  const res = http.get(`${BASE_URL}/api/volume/cached`, { tags: { action: 'rank' } });
  rankDuration.add(res.timings.duration);
  check(res, { '랭킹 조회 200': (r) => r.status === 200 });
}

function doDetail(stockPool) {
  const stock = randomStock(stockPool);
  const res = http.get(`${BASE_URL}/api/stock/${stock.code}/cached`, { tags: { action: 'detail' } });
  detailDuration.add(res.timings.duration);
  // 워밍업 직후라 아직 캐시가 안 찼으면 204도 정상 - 부하가 걸린 게 아니라 웜업 타이밍 문제이므로 실패로 안 침
  check(res, { '상세 조회 200/204': (r) => r.status === 200 || r.status === 204 });
}

function doBuy(stockPool, priceMode) {
  const stock = randomStock(stockPool);
  const price = getCachedPrice(stock.code) || stock.price;
  if (!price) return;

  // 지정가의 30%는 현재가보다 살짝 낮게 넣어서 "바로 체결 안 되고 대기(PENDING)로 남는" 경로와
  // 5% 자동 가격개선 밴드 경계 부근 매칭도 같이 부하를 태운다.
  const orderPrice = priceMode === 'LIMIT' && Math.random() < 0.3
    ? roundTick(price * 0.98)
    : price;

  // 계좌 시드머니(1천만원)는 고정이라, 한 계정이 반복적으로 매수를 계속 내면 언젠가 잔고부족(400)을 정상적으로 맞는다.
  // 이건 앱이 제대로 거절한 것이지 서버 결함이 아니므로 http_req_failed에서는 제외하고, 성공률은 order_success_rate로 따로 추적한다.
  const res = submitOrder({
    stockCode: stock.code,
    stockName: stock.name,
    orderType: 'BUY',
    priceMode,
    price: orderPrice,
    quantity: 1 + Math.floor(Math.random() * 5),
  }, sessionCookie, { action: `buy_${priceMode.toLowerCase()}` }, [200, 400]);

  orderDuration.add(res.timings.duration);
  const ok = res.status === 200;
  orderSuccessRate.add(ok);
  check(res, { [`매수(${priceMode}) 200/400`]: (r) => r.status === 200 || r.status === 400 });
}

function doSell(stockPool, isSeller) {
  const stock = stockPool[0]; // 시딩을 이 종목에 몰아뒀으므로 매도도 같은 종목으로
  const price = getCachedPrice(stock.code) || stock.price;
  if (!price) return;

  if (isSeller) {
    // 시딩된 보유수량(50주)도 반복 매도하다 보면 바닥날 수 있음 - 그 경우의 400도 정상 거절이라 expectedStatuses에 포함
    const res = submitOrder({
      stockCode: stock.code,
      stockName: stock.name,
      orderType: 'SELL',
      priceMode: Math.random() < 0.5 ? 'MARKET' : 'LIMIT',
      price,
      quantity: 1 + Math.floor(Math.random() * 3),
    }, sessionCookie, { action: 'sell' }, [200, 400]);
    orderDuration.add(res.timings.duration);
    const ok = res.status === 200;
    orderSuccessRate.add(ok);
    check(res, { '매도 200/400': (r) => r.status === 200 || r.status === 400 });
  } else {
    // 보유수량이 없는 유저의 매도 시도 - 500이 아니라 400으로 깔끔히 막히는지가 관심사라
    // 400/200을 모두 "정상 처리"로 보고 5xx만 실패로 카운트한다.
    const res = submitOrder({
      stockCode: stock.code,
      stockName: stock.name,
      orderType: 'SELL',
      priceMode: 'MARKET',
      price,
      quantity: 1,
    }, sessionCookie, { action: 'sell_reject' }, [200, 400]);
    check(res, { '보유수량 부족 시 400/200 (5xx 아님)': (r) => r.status === 200 || r.status === 400 });
  }
}

/* ---------------- 상황3: FDS 어뷰저 ---------------- */
export function fdsAbuser(data) {
  const a = data.abusers[(__VU - 1) % data.abusers.length];
  if (__ITER === 0) sessionCookie = login(a.memberId, PASSWORD);

  const mode = __VU % 3;
  if (mode === 0) abuseFrequency(data.stockPool);
  else if (mode === 1) abusePriceOutlier(data.stockPool);
  else abuseRoundTrip(data.stockPool);

  sleep(5);
}

// 규칙1(order-frequency): window-seconds=60, threshold=10 -> 60초 안에 12건을 쏴서 확실히 넘긴다.
// 12건 연사를 반복하면 계정 시드머니가 금방 바닥나서 뒤로 갈수록 400(잔고부족)이 정상적으로 뜬다 - expectedStatuses로 처리
function abuseFrequency(stockPool) {
  const stock = stockPool[0];
  for (let i = 0; i < 12; i++) {
    const res = submitOrder({
      stockCode: stock.code, stockName: stock.name,
      orderType: 'BUY', priceMode: 'MARKET', price: getCachedPrice(stock.code) || stock.price,
      quantity: 1,
    }, sessionCookie, { action: 'fds_frequency' }, [200, 400]);
    check(res, { 'FDS 빈도어뷰저 주문 200/400': (r) => r.status === 200 || r.status === 400 });
    fdsOrdersSent.add(1, { rule: 'frequency' });
    sleep(3);
  }
}

// 규칙3(price-outlier): threshold-percent=20 -> 현재가 대비 30% 낮은 지정가로 제출(체결 여부와 무관하게 주문 시점에 탐지됨)
function abusePriceOutlier(stockPool) {
  const stock = randomStock(stockPool);
  const price = getCachedPrice(stock.code) || stock.price;
  const res = submitOrder({
    stockCode: stock.code, stockName: stock.name,
    orderType: 'BUY', priceMode: 'LIMIT', price: roundTick(price * 0.7),
    quantity: 1,
  }, sessionCookie, { action: 'fds_price_outlier' }, [200, 400]);
  check(res, { 'FDS 가격이상치어뷰저 주문 200/400': (r) => r.status === 200 || r.status === 400 });
  fdsOrdersSent.add(1, { rule: 'price_outlier' });
}

// 규칙2(round-trip): window-seconds=60 -> 시딩해둔 보유수량으로 매도 후 곧바로 재매수해서
// "반대 방향 체결이 60초 내에 이미 있음" 조건을 만든다. 두 다리 모두 MARKET이라 즉시 체결 확률이 높다.
function abuseRoundTrip(stockPool) {
  const stock = stockPool[0]; // 시딩된 종목
  const price = getCachedPrice(stock.code) || stock.price;

  const sellRes = submitOrder({
    stockCode: stock.code, stockName: stock.name,
    orderType: 'SELL', priceMode: 'MARKET', price, quantity: 1,
  }, sessionCookie, { action: 'fds_roundtrip' }, [200, 400]);
  check(sellRes, { 'FDS 왕복매매어뷰저 매도 200/400': (r) => r.status === 200 || r.status === 400 });
  fdsOrdersSent.add(1, { rule: 'round_trip' });

  sleep(3); // 매도 체결이 처리될 시간

  const buyRes = submitOrder({
    stockCode: stock.code, stockName: stock.name,
    orderType: 'BUY', priceMode: 'MARKET', price, quantity: 1,
  }, sessionCookie, { action: 'fds_roundtrip' }, [200, 400]);
  check(buyRes, { 'FDS 왕복매매어뷰저 매수 200/400': (r) => r.status === 200 || r.status === 400 });
  fdsOrdersSent.add(1, { rule: 'round_trip' });
}

/* ---------------- 공통 헬퍼 ---------------- */
function randomStock(stockPool) {
  return stockPool[Math.floor(Math.random() * stockPool.length)];
}

// /api/stock/{code}/cached는 permitAll이라 인증이 필요 없음 - 쿠키 유무와 무관하게 동작한다.
function getCachedPrice(code) {
  const res = http.get(`${BASE_URL}/api/stock/${code}/cached`, { tags: { action: 'get_cached_price' } });
  check(res, { '현재가 조회 200/204': (r) => r.status === 200 || r.status === 204 });
  if (res.status !== 200) return null;
  const dto = res.json();
  return parseInt(dto.stckPrpr, 10) || null;
}

function roundTick(price) {
  return Math.max(1, Math.round(price / 10) * 10);
}

// cookie는 auth.js의 login()이 리턴한 JSESSIONID 값(VU 모듈 스코프의 sessionCookie).
// k6의 자동 쿠키 저장소는 iteration이 바뀌면 세션 쿠키를 비우므로 여기서 매번 직접 붙인다(issue.md 참고).
// redirects: 0으로 자동 추적을 꺼서, 세션이 깨졌을 때 나오는 302가 조용히 200(로그인 페이지)으로
// 마스킹되지 않고 원래 상태코드 그대로 드러나게 한다.
function submitOrder(order, cookie, tags, expectedStatuses) {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      ...(cookie ? { Cookie: `JSESSIONID=${cookie}` } : {}),
    },
    tags: tags || {},
    redirects: 0,
  };
  if (expectedStatuses) {
    params.responseCallback = http.expectedStatuses(...expectedStatuses);
  }
  return http.post(`${BASE_URL}/api/order/create`, JSON.stringify(order), params);
}
