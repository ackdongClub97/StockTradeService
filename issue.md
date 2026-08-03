# Issue: KIS 실시간 웹소켓 구독 한도(40종목) 초과 시 호가/체결 영구 중단 - 체결가 구독 제거로 해결

## 발견 경위

상세페이지 호가창이 "너무 펼쳐져 있고 반영이 잘 안 되는 것 같다"는 피드백을 조사하던 중, `KisWebSocketService`의 `MAX_SUBSCRIPTIONS = 40` 캡을 살펴보다가 훨씬 심각한 문제를 발견함.

## 문제

`subscribe(code)`는 종목 코드 1개당 체결가(H0STCNT0)와 호가(H0STASP0) 두 채널을 동시에 구독 신청한다(`sendSubscribe()`). `subscribedCodes`는 **서버가 켜진 뒤 지금까지 한 번이라도 조회/주문된 서로 다른 종목 코드의 누적 집합**이라 절대 줄어들지 않는데(구독 해제/이빅션 로직이 없음), 이 집합이 40개를 넘으면 `subscribe()`가 아무 것도 하지 않고 조기 리턴한다.

이때 체결가(현재가)는 마이페이지 보유종목 조회나 뉴스분석 시점에 우연히 REST로 채워질 여지라도 있지만, **호가(주문 체결의 유일한 근거)는 이 앱 전체에 REST 조회 경로 자체가 없다** — 오직 H0STASP0 웹소켓 프레임으로만 채워짐. 즉 41번째로 새로 등장하는 종목은:
- 호가창이 영원히 빈 화면으로 남고
- `MatchingService`(`matching()`/`tryMatch()`)도 `getCachedAskingPrice()`가 계속 null이라 `NO_ORDERBOOK_DATA`로만 표시하며 **매수/매도 주문이 서버 재시작 전까지 영구적으로 체결되지 않는다.**

단순 UI 버그가 아니라 실거래(모의) 기능 자체가 막히는 심각도임.

## 원인

1. `subscribedCodes`가 "지금 보고 있는 종목"이 아니라 "오늘 하루 누적으로 한 번이라도 다룬 종목"을 세고 있어서, 실사용자가 많아질수록 캡에 더 빨리, 더 영구적으로 도달함.
2. 캡을 넘은 종목에 대한 복구 경로(REST 폴백, 재시도, 알림)가 전혀 없음.
3. (부차적으로 확인된 사실) 종목 1개당 체결가+호가 두 채널을 등록하기 때문에, KIS가 문서화한 "세션(approval_key)당 실시간 등록 41건" 제한을 기준으로 보면 앱 자체의 40종목 캡보다 **KIS 쪽 실제 등록 거부가 약 20종목 지점에서 먼저 발생했을 가능성**이 있음(40종목 × 2채널 = 80건 요청 vs 41건 한도). 다만 이건 코드에서 KIS의 구독 성공/실패 응답을 파싱해서 확인하는 로직이 없어 로그(`handleControlMessage`)로만 남고 실제 검증은 못 함.

## 해결

체결가(H0STCNT0) 구독 자체를 없애고 호가(H0STASP0) 하나만 구독하도록 변경 — 종목당 실시간 등록 건수를 2건→1건으로 줄여 같은 KIS 한도 안에서 캡을 사실상 2배로 완화함.

- 매칭엔진(`MatchingService`)은 원래부터 호가만 사용하고 체결가는 전혀 참조하지 않았음(코드로 확인) — 매칭 로직은 변경 없음.
- 화면에 필요했던 현재가/등락률/전일대비는 체결가 없이도 재현 가능: 호가 최우선단계(매도1·매수1)의 중간값을 현재가로 근사하고, REST 웜업 시점에 캐시해둔 전일종가(`stckPrpr - prdyVrss`)와 비교해서 매 호가 틱마다 계산(`KisWebSocketService.pushDerivedDetail()`).
- 누적거래량은 호가 프레임에 아예 없는 값이라(체결 틱 전용 필드) 유일하게 REST로 남겨둠 — 구독 중인 종목에 한해 30초마다 갱신(`refreshDailyStats()`, 기존 `MatchingService.matching()`과 동일한 배치 주기). 갱신 전까지는 최대 30초 지연될 수 있다는 트레이드오프를 감수.
- 관련 파일: `KisWebSocketService.java` (`sendSubscribe`에서 `TR_CCNL` 제거, `handleCcnl` 삭제, `DailyStats` 캐시 + `cacheDailyStats`/`pushDerivedDetail`/`deriveCurrentPrice`/`refreshDailyStats` 추가).

## 검증

- `./gradlew compileJava` 통과.
- `./gradlew test` 13건 중 12건 통과 — 나머지 1건(`StockTradeApplicationTests`)은 로컬에서 앱이 이미 떠서 H2 파일(`~/stockTrade.mv.db`)을 잠그고 있어 발생하는 기존 현상(위 "DB 커넥션 풀 고갈" 이슈와 무관, 테스트 실행 시 항상 나던 것)이라 이번 변경과 무관.
- 앱 재시작 후 Claude in Chrome으로 `/stock/005930` 직접 확인: 서버 로그에 `구독 요청: H0STASP0 / 005930`만 나가고(체결가 요청 없음) KIS가 `SUBSCRIBE SUCCESS` 응답. 현재가(244,250원)가 호가 매도1(244,500)·매수1(244,000)의 정확한 중간값으로 표시됨, 등락률(-6.95%)·전일대비(-18,250원)·누적거래량(1,449만주, 시간 지나며 갱신됨) 모두 정상 표시.
- MAX_SUBSCRIPTIONS 캡의 실제 KIS 쪽 한도(41건/세션) 자체는 이번에 검증하지 못함 - 필요시 KIS 공식 문서 재확인 또는 20~41번째 구독 시점의 제어 메시지 응답을 직접 로그로 확인 필요.

---

# Issue: DB 커넥션 풀(HikariCP) 고갈

## 발견 경위

마이페이지 "이번달 매매차익" 그래프에서 막대가 안 보인다는 문제를 조사하던 중 발견됨.
(참고: 그래프 자체는 별개의 UI 버그였고 이미 해결됨 — 아래 "관련이지만 별개로 해결된 이슈" 참고)

브라우저에서 `/api/order/realized-pnl`을 직접 호출해보니 500 에러:

```
org.springframework.dao.DataAccessResourceFailureException:
Unable to acquire JDBC Connection
[HikariPool-1 - Connection is not available, request timed out after 30000ms
(total=10, active=10, idle=0, waiting=1)]
```

커넥션 풀 10개가 전부 active 상태로 물려있고 하나도 안 풀림. 5초 뒤 재시도해도 동일 — 저절로 회복되지 않는 상태.

## 예상되는 원인

가장 유력한 용의자: **`GET /api/order/holding/stream`** (`OrderController.streamHolding`)

- 이 SSE 스트림은 브라우저 탭이 마이페이지/홈 화면(보유주식 탭)을 열어두는 동안 **계속 연결이 유지**됨.
- 구독 중인 종목의 가격이 틱 올 때마다(1초 샘플링) `computeHolding()` → `RealizedPnlService.getPositions()` → `walk()`가 실행되는데, `walk()`가 캐싱 없이 **매번 새 DB 쿼리**(`TradeRepository.findByMemberIdOrderByMatchedAtAsc`)를 날림.
- SSE 연결은 브라우저 탭이 **정상적으로 닫힐 때만** 서버 쪽에서 구독이 취소(cancel)됨. 탭이 비정상 종료되거나(강제 새로고침 연타, 자동화 테스트로 탭을 계속 새로 만들고 정리 안 함, 네트워크 순간 끊김 등) 서버가 연결 끊김을 즉시 감지 못하면, 해당 Flux 구독이 서버에서 안 죽고 **1초마다 계속 DB를 두드리는 "좀비 스트림"**으로 남을 수 있음.
- 오늘 세션에서 페이지 새로고침/재접속/앱 재시작이 매우 많았음(수동 테스트 + 브라우저 자동화 포함) — 이런 좀비 스트림이 여러 개 누적되며 커넥션 풀을 서서히 잠식했을 가능성이 높음.

부차적 요인: `computeHolding()`의 캐시 미스 시 REST 폴백 경로가 `kisService.getStockDetail(...).block()`로 리액티브 파이프라인(`.map()`) 안에서 동기 블로킹 호출을 함. DB 커넥션을 직접 누수시키진 않지만, 스레드를 묶어두는 안티패턴이라 상황을 더 악화시켰을 수 있음.

## 예상 임팩트

- 커넥션 풀이 고갈되면 **DB를 쓰는 모든 API가 500 에러**를 냄 — `/api/order/realized-pnl`뿐 아니라 `/api/order/holding`, `/api/order/history`, `/api/order/create` 등 order 관련 기능 전반이 영향권.
- 앱을 재시작하기 전까지 저절로 회복되지 않음.
- 마이페이지 보유종목/매매차익, 주문 생성/취소 등 핵심 기능이 사실상 전부 마비되는 심각도.

## 조치 상황

- [x] 그래프 자체의 "막대가 안 보이는" 문제는 별개 원인으로 확인 및 수정 완료: 실제로는 막대가 정상 렌더링되고 있었으나, 최신 날짜(가장 오른쪽 막대)가 가로 스크롤 영역 밖으로 밀려나 안 보였던 것. `myPage.html`의 `renderPnlChart()`에서 렌더링 후 `container.scrollLeft = container.scrollWidth`로 자동 스크롤하도록 수정함.
- [x] **`holding/stream`의 초당 DB 재조회 문제 완화**: `RealizedPnlService.walk()`에 회원 단위 짧은 캐시(TTL 1.5초) 추가. 같은 회원에 대해 1.5초 안에 다시 호출되면 DB를 안 타고 캐시된 결과를 반환. `MatchingService.execute()`에서 실제 체결(Trade insert)이 발생하는 즉시 `realizedPnlService.invalidate(memberId)`로 해당 회원 캐시를 지워서, 방금 체결된 내역이 반영 안 되는 지연 없이 정확성은 유지하면서 반복 조회만 줄임.
  - 관련 파일: `RealizedPnlService.java`(캐시+`invalidate()` 추가), `MatchingService.java`(체결 시 invalidate 호출 연결)
  - 컴파일 확인 완료. 실행 후 재현 테스트는 아직 안 함.
- [ ] 다음 단계로 남은 것:
  1. 앱 재시작으로 현재 고갈된 풀 우선 해소, 캐시 적용 후 재발 여부 관찰
  2. 그래도 active 커넥션이 계속 늘어나면, SSE 연결이 끊겼을 때 서버 쪽 구독(Flux)이 실제로 정리되는지(좀비 스트림 여부) 별도 확인 필요 — 이번 조치는 "쿼리 빈도"만 줄인 것이라, 좀비 스트림 자체가 원인이었다면 완전한 해결책은 아님
  3. `computeHolding()`의 `.block()` 블로킹 호출도 논블로킹 방식으로 정리 검토

## 진행 상황 (2026-07-30)

실사용자를 받을 계획이 생기면서 다시 짚어봄. 현재 시점 기준:

- **정확한 누수 원인은 아직 특정 못함.** 위 "좀비 SSE 스트림" 가설은 여전히 가장 유력한 후보일 뿐, 실제로 재현/확인된 건 아님.
- **그렇다고 지금 문제가 없는 건 아님.** 최초 발견 이후로 별도 인시던트(풀 재고갈)가 재발한 적은 없지만, 이건 "고쳐졌다"는 뜻이 아니라 그 뒤로 부하가 그 정도로 몰린 적이 없었을 가능성이 커서 — 안심할 근거는 아님.
- HikariCP `maximum-pool-size`를 (예: 10 → 20으로) 올리는 방안도 검토했으나, 근본 원인(누수/과도한 조회)을 안 고친 채 숫자만 올리면 고갈까지 걸리는 시간만 늘어날 뿐 재발 자체는 못 막는다고 판단 — 보류.
- **결정: 지금 당장 조치(풀 사이즈 변경 등)하기보다, 우선 관찰부터 한다.**
  - 관찰 방법: 이번에 새로 만든 어드민 화면(`/admin/orders`)에서 미체결 주문의 `SYSTEM_ERROR` 사유 발생 여부/빈도 확인, Grafana+Loki(`observability/`)로 실사용자 트래픽 하에서 커넥션 관련 로그·에러 패턴 관찰.
  - 관찰 결과 실제로 좀비 스트림/누수가 확인되면 그때 근본 수정(SSE 구독 정리 로직 점검, 필요시 풀 사이즈 조정)을 진행할 예정.

---

# Issue: 매수 지정가 기능 업데이트 중 발생한 트러블슈팅 - 작은 화면에서 주문 팝업이 잘림

## 문제

매수 지정가(현재가/지정가 토글, 자동 가격개선 안내 체크박스, 설명·경고 문구) UI를 추가하면서 주문 팝업(`#tradeModal .modal-content`)의 세로 길이가 늘어났는데, 모니터 화면(뷰포트) 높이가 작은 환경에서는 팝업 하단(주문 버튼 포함)이 화면 밖으로 밀려 잘리고 스크롤할 방법도 없어 주문 자체를 못 누르는 상태가 됨.

## 원인

`.modal-content`에 높이 제한(`max-height`)이나 `overflow` 속성이 전혀 없었음. 배경 오버레이인 `.modal`은 `position: fixed`로 뷰포트 전체를 덮지만 그 자체에도 스크롤 설정이 없어서, 내부 `.modal-content`가 뷰포트보다 커지면 초과분이 그대로 화면 밖으로 넘어가 시각적으로 사라짐(스크롤 불가능하게 잘림).

## 해결

`.modal-content`에 `max-height` + `overflow-y: auto`(+`overflow-x: hidden`)를 추가해서, 내용이 뷰포트를 넘칠 때만 팝업 내부에서 자체적으로 스크롤되게 함. 처음엔 `max-height: 85vh`로 잡았는데, 화면이 충분히 큰 환경에서도 팝업 기본 높이가 줄어드는 것처럼 보인다는 피드백을 받아 `94vh`(모바일은 `92vh`)로 올려서, 화면이 실제로 부족할 때만 스크롤이 개입하고 평소엔 기존과 동일한 높이로 보이도록 재조정.

관련 파일: `stockDetail.css`(`.modal-content` 및 모바일 미디어쿼리 내 `.modal-content`).

CSS 변경이라 컴파일 불필요. 실제 작은 화면에서의 렌더링 재현 테스트는 아직 브라우저로 안 해봄.

---

# Issue: 랭킹 캐시 무한 누적으로 인한 화면 렌더링 지연

## 발견 경위

"FDS/뉴스분석 기능을 추가한 이후로 로컬 화면이 새로고침·개발자도구 토글·화면전환 시 10~20초씩 느려졌다"는 제보로 시작. 처음엔 최근에 추가한 FDS(주문 시점 DB 조회 추가)를 의심했음.

조사 순서:
1. 서버 CPU(0%)/메모리(정상)/응답시간(수십~수백ms) 확인 — 전부 정상, FDS 관련 알림/에러 로그도 최근 1시간 동안 0건.
2. 로그인 여부와 무관하게 동일 증상이라는 진술을 받고, `holding/stream`·`order/stream`(둘 다 로그인 필요)에 연결/종료 로그를 추가해 확인 — 두 스트림 모두 연결 자체가 0건이었음 → SSE 좀비 연결 가설 기각.
3. Claude in Chrome으로 `localhost:8080/stockHome`을 직접 열어 DOM을 조사 — **DOM 노드 264,259개, `<tr>` 15,540개**. 정상이라면 랭킹 테이블은 상위 30개 안팎이어야 함.
4. 기존 `GET /api/volume/cached` 엔드포인트로 서버 메모리의 랭킹 캐시 자체를 직접 확인 — `count: 15540`, `dataRank`가 1~30을 계속 반복 → 상위 30개 리스트가 약 518번 이어붙여진 상태임을 확인.

## 원인

- `KisService.restartFromDb()`(앱 기동 시 1회 호출, 오늘 날짜 랭킹 데이터가 아직 없을 때 "가장 최근 데이터"로 캐시를 채우는 용도)가 `StockRepository.findToByOrderByDate()`를 호출하는데, 이 쿼리에 **날짜 필터가 없어서** 실제로는 `STOCK` 테이블에 쌓인 **모든 날짜**의 EOD 스냅샷을 통째로 반환하고 있었음. 메서드 이름과 주석("가장 최근 데이터 조회")의 의도와 실제 구현이 어긋나 있었던 것.
- 게다가 `cachedData.addAll(dtos)`만 있고 `clear()`가 없어서, 이 잘못된 대량의 리스트가 그대로 랭킹 캐시(`KisService.cachedData`)에 쌓임.
- 이 프로젝트가 여러 날에 걸쳐 개발되면서 `STOCK` 테이블에 하루치씩 EOD 스냅샷이 계속 누적됐고, 그 날짜 수만큼 캐시가 부풀어 약 15,540개(≈30개 × 518일치)까지 커진 상태로 방치돼 있었음.
- **FDS/뉴스분석 기능과는 무관하게 그 이전부터 존재했던 버그**이고, 이번에 데이터가 충분히 쌓이면서 처음으로 체감될 만큼 커진 것으로 판단.

## 증상/임팩트

- 부풀려진 랭킹 리스트가 SSE(`/api/volume/stream`)로 그대로 브라우저에 전달되어 `stockHome.html`의 `renderTable()`이 매번 15,540개 행을 렌더링 → DOM 노드 26만 개.
- 새로고침, 화면전환, 개발자도구 토글(뷰포트 리사이즈 유발) 등 브라우저가 이 거대한 DOM을 다시 레이아웃/페인트해야 하는 모든 순간마다 체감 10~20초 지연 발생.
- 서버 자체(CPU/메모리/DB 커넥션 풀)에는 영향 없음 — 순수하게 클라이언트 브라우저 렌더링 부하 문제였음.

## 해결

- `StockRepository`: `findToByOrderByDate()`를 제거하고, 서브쿼리로 최신 날짜 하나만 정확히 골라내는 `findLatestDateSnapshot()`로 교체.
  ```java
  @Query("SELECT s FROM Stock s WHERE s.date = (SELECT MAX(s2.date) FROM Stock s2)")
  List<Stock> findLatestDateSnapshot();
  ```
- `KisService.restartFromDb()`: 위 메서드로 교체하고, `cachedData.addAll()` 전에 `cachedData.clear()`를 방어적으로 추가(현재는 1회성 호출이지만 재발 방지 차원).
- `STOCK` 테이블의 기존 이력 데이터 자체는 건드리지 않음 — 유효한 과거 기록이라 삭제할 이유 없음. 문제는 "여러 날짜를 한꺼번에 읽어오는 쿼리"였을 뿐 데이터 자체는 정상.

## 검증

- `./gradlew compileJava compileTestJava` 통과, `./gradlew test` 13건 중 12건 통과(나머지 1건은 IntelliJ에 이미 떠있는 인스턴스의 H2 파일 락 충돌로, 이 변경과 무관한 기존 현상).
- 코드 수정만으로는 **이미 떠 있는 서버의 메모리 캐시(15,540개)가 저절로 줄어들지 않음** — `restartFromDb()`는 앱 기동 시 1회만 실행되므로, 서버 재시작 후에야 정상화된 캐시로 다시 채워짐.
- [ ] 재시작 후 `GET /api/volume/cached`의 `count`가 30 안팎으로 돌아오는지, `stockHome`의 DOM 노드 수가 정상 범위로 줄었는지 재확인 필요(사용자 재시작 대기 중).

## 교훈

- 증상이 났을 때 가장 최근에 바꾼 코드(FDS)부터 의심하는 건 합리적인 출발점이었지만, 서버 CPU/로그/SSE 연결 상태를 하나씩 확인하며 "증거가 없으면 다음 가설로" 넘어간 끝에 전혀 다른 위치의 훨씬 오래된 버그를 찾아냄. `findToByOrderByDate` 같은 Spring Data 파생 쿼리 메서드는 이름만 보고 의도를 짐작하기 쉬운데, 실제로 어떤 조건으로 걸러지는지 항상 확인이 필요하다는 사례.
