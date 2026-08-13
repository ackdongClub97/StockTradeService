# 부하테스트 시나리오

## 목표

- 동시 접속자 500명 기준 부하테스트
- 커버 범위: 주식 랭킹 조회, 단일 종목 상세 조회, 매수 주문(지정가/현재가, 지정가는 5% 자동 가격개선 포함), 매도 주문
- FDS(이상거래탐지)가 부하 상황에서도 정상 반응하는지 같이 검증

도구: k6. 스크립트 위치: `loadtest/k6/` (`config.js`, `lib/auth.js`, `main.js`).

## 사전 준비 (코드 변경)

`GET /api/stock/{code}/cached` 신규 엔드포인트를 `KisController`에 추가했다. 기존 `GET /api/stock-detail`은 캐시를 안 타고 매 호출마다 실제 KIS(모의투자) REST를 직접 호출하는 구조라, 500명이 "단일 건 조회"를 반복하면 KIS 쪽 레이트리밋에 걸리거나 외부 API에 그대로 부하가 나갈 위험이 있었음. 신규 엔드포인트는 `kisService.getCachedStockData()`만 읽고, 캐시가 비어있으면 `kisWebSocketService.subscribe()`로 웜업만 걸어둔다(1회성 REST 웜업은 기존 구조 그대로 재사용). 부하테스트의 상세조회 시나리오는 이 엔드포인트로만 진행한다.

## 시나리오 구성

### 1) 일반 유저 500명 동시접속 (`normal_users` scenario)

- 실행 방식: ramping-vus, 2분 램프업 → 6분 유지 → 1분 램프다운 (총 9분)
- 종목 풀: 랭킹 상위 20개로 고정(`STOCK_POOL_SIZE`) — `KisWebSocketService`의 실시간 구독 상한(`MAX_SUBSCRIPTIONS=40`)을 넘기지 않기 위함. 넘기면 해당 종목은 캐시가 안 채워져서 상세조회가 계속 204만 반환함.
- 액션 가중치(1회 반복당 하나만 수행, think-time 1~3초):
  - 랭킹 조회 40% — `GET /api/volume/cached`
  - 단일 종목 상세 조회 30% — `GET /api/stock/{code}/cached`
  - 매수(지정가) 12% — `POST /api/order/create`, `priceMode=LIMIT`. 이 중 30%는 현재가보다 2% 낮게 주문해서 "즉시 체결 안 되고 PENDING으로 남는" 경로 + 5% 자동 가격개선 밴드 경계 매칭도 같이 부하를 태움
  - 매수(현재가) 8% — `priceMode=MARKET`
  - 매도 10% — 보유수량 있는 유저는 정상 매도(200 기대), 없는 유저는 그대로 시도해서 500이 아니라 400으로 깔끔히 막히는지 검증(5xx만 실패로 카운트)

매도 시나리오를 위해 `setup()` 단계에서 유저의 30%(`SELLER_RATIO`)에게 미리 MARKET 매수로 보유수량을 시딩해둔다.

### 2) FDS 반응 검증 (`fds_abusers` scenario)

- 실행 방식: constant-vus 6개, 일반 유저 램프업이 끝나는 2분 시점부터 6분간 실행(정상 트래픽 위에 얹혀서 탐지되는지 확인)
- FDS 3규칙을 각각 겨냥:
  1. **주문빈도**(`order-frequency`, 임계 10건/60초) — 3초 간격으로 12건 연속 매수 → 60초 내 임계 초과
  2. **가격이상치**(`price-outlier`, 임계 20%) — 현재가 대비 30% 낮은 지정가로 매수 제출. 체결 여부와 무관하게 주문 제출 시점(`checkOnSubmit`)에 탐지됨
  3. **왕복매매**(`round-trip`, 60초 내 반대매매) — 시딩된 보유수량으로 매도 후 3초 뒤 재매수(둘 다 MARKET이라 즉시체결 확률 높음)
- 검증 방법: 테스트 중/직후 `GET /api/admin/alerts/stream`(SSE)로 실시간 확인하거나, `GET /api/admin/logs`(Loki)에서 `[FDS]` 태그로 검색. 일반 유저 트래픽에서 오탐(빈도 규칙 오발동 등)이 없는지도 같이 확인.

## 실행 전 체크리스트

- **장 시간에 실행**: 주문 접수(`POST /api/order/create`)는 08:00부터 가능하지만, 실제 체결(`tryMatch`/30초 배치)은 `KisService.isMarketOpen()`이 true인 평일 09:00~15:30에만 동작함(`Asia/Seoul`). 장 시간 밖에서 돌리면 전부 PENDING으로만 쌓임.
- **HikariCP 커넥션 풀**: 현재 별도 설정이 없어 기본값(10). 500 동시접속 + H2 파일 DB 조합에서 커넥션 경합/타임아웃 가능성이 있으므로, 테스트 전 `spring.datasource.hikari.maximum-pool-size`를 올려두는 것을 권장(테스트 실행 전 판단 필요, 아직 미적용).

## 실행 방법

```bash
k6 run loadtest/k6/main.js -e BASE_URL=http://localhost:8080 -e USER_COUNT=500
```

주요 파라미터(`config.js`, `-e KEY=VALUE`로 오버라이드 가능): `USER_COUNT`, `FDS_ABUSER_COUNT`, `SELLER_RATIO`, `STOCK_POOL_SIZE`, `BASE_URL`.

## 임계치(thresholds)

- `rank_duration` p95 < 500ms
- `detail_duration` p95 < 500ms
- `order_duration` p95 < 1500ms
- `http_req_failed` rate < 1%
- `order_success_rate` rate > 95%

## 실행 결과 (2026-08-05 15:0x KST, 장중, HikariCP 기본값 그대로)

원본 k6 출력 로그: `loadtest/k6/results/2026-08-05_500users.log`

총 소요시간 9m30.5s (`setup()` 약 30초 + `normal_users` 9분, `fds_abusers`는 그 안에 겹쳐서 6분).

- `rank_duration` / `detail_duration` / `order_duration` 전부 p95 1~3ms — 임계치(500ms/1500ms)에 한참 못 미침. 캐시 경유 상세조회 엔드포인트가 의도대로 동작.
- `order_success_rate` 99.94%, 전체 `checks_succeeded` 99.98%.
- **`http_req_failed` 4.12%로 임계치(<1%) 미달** — 유일하게 실패한 항목. 응답시간이 끝까지 낮게 유지됐고 5xx/타임아웃 흔적이 없어서 서버 성능 문제는 아니고, 스크립트 쪽 원인으로 판단:
  1. FDS 빈도어뷰저(`abuseFrequency`, 60초에 12건 연사)가 반복되면 계정 시드머니(1천만원)를 금방 소진해서 이후 주문이 400(잔고부족)으로 정상 거절되는데, 원래 스크립트에는 이 호출들에 `check`/`expectedStatuses`가 없어서 정상 거절인데도 `http_req_failed`에 그대로 잡혔음.
  2. `setup()`의 회원가입 batch(506건)도 마찬가지로 체크가 없어서, H2에 이전 실행의 `loadtest_*` 계정이 남아있는 채로 재실행하면 "이미 사용 중인 아이디" 400도 같이 섞여 들어감.
  3. (별도 발견) 시딩 매수 수량이 50주로 고정되어 있어서, `stockPool[0]`이 고가주(예: 삼성전자 24만원대)로 뽑히면 `50주 × 24만원 = 1,240만원`이 시드머니(1천만원)를 넘어서 시딩 자체가 실패할 수 있는 잠재 버그가 있었음. 이번 실행은 운 좋게 시딩이 대부분 성공(매도 성공률 99.6%, 3865/3879)해서 드러나지 않았음.
- 위 3가지를 `loadtest/k6/main.js`에서 수정함: 잔고/보유수량 부족으로 인한 400을 `http.expectedStatuses(200, 400)`으로 명시 처리(진짜 실패와 구분), 시딩 수량을 `min(50, floor(9000000 / 종목단가))`로 계산해서 고가주가 뽑혀도 시드머니를 안 넘도록 변경.
- FDS 알림 자체(Loki/`/api/admin/alerts/stream`)는 이번 실행에서 실시간으로 확인 안 함 — `fds_triggering_orders_sent` 카운터로 522건 전송된 것만 확인. 다음 실행 때 알림 스트림을 같이 열어두고 3규칙이 실제로 찍히는지 확인 필요.

## 아직 안 한 것 / 다음에 확인할 것

- 위 수정 반영한 스크립트로 재실행해서 `http_req_failed`가 임계치 안으로 들어오는지 확인
- FDS 알림 스트림(`/api/admin/alerts/stream` 또는 Loki `[FDS]` 로그)로 3규칙 실제 발동 여부 확인 — 아직 안 함
- HikariCP 풀 크기 조정 여부 미결정(이번 실행은 응답시간이 워낙 낮아서 커넥션 경합 징후는 없었음)
