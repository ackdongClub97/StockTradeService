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

---

# Issue: 종목 상세페이지 실시간 시세가 재접속(새로고침) 시 표시되지 않음

## 문제

종목 상세페이지에서 실시간 시세(체결가) 로딩이 끝나지 않거나, 새로고침해도 계속 데이터가 안 뜸. 서버 로그에는 다음과 같은 KIS 호출 실패가 찍혀 있었음:

```
[KisWS] 구독 요청: H0STCNT0 / 005930
ERROR ... KIS ERROR BODY = {"rt_cd":"1","msg1":"초당 거래건수를 초과하였습니다.","msg_cd":"EGW00201", ...}
WARN  ... [KisWS] 005930 웜업 조회 실패: {...EGW00201...}
```

"새로고침하면 다시 나오겠지" 하고 새로고침해봐도 여전히 데이터가 안 나옴.

## 원인

두 가지가 겹쳐 있었음.

1. **KIS 초당 호출 제한(EGW00201) 에러에 대한 재시도가 없었음.** 기존 `getVolumeRank`/`getStockDetail`의 재시도 로직(`Retry.backoff`)은 `PrematureChannelClosureException`(연결 조기 종료)만 재시도 대상으로 삼고 있어서, rate-limit 에러는 한 번 실패하면 그걸로 끝. 그리고 프론트엔드는 로딩 오버레이를 10초 뒤 안전장치로 그냥 숨기기만 할 뿐, 데이터가 실제로 왔는지 여부와 무관하게 조용히 넘어가서 "로딩은 끝났는데 화면엔 아무것도 없는" 상태가 됐음.

2. **(더 근본적인 원인) `KisService.getStockDetailStream()`가 연결이 끊길 때마다 `detailSink`에서 해당 종목의 Sink를 지우고 있었음.** 반면 `KisWebSocketService.subscribedCodes`는 한 번 구독된 종목을 계속 구독 상태로 유지하며, 이미 구독된 종목이면 웜업(REST) 조회를 다시 하지 않는 구조. 그래서 새로고침 → 새 빈 Sink 생성 → `subscribe()`는 "이미 구독중"이라 웜업 재조회 안 함 → 장중이면 곧 새 웹소켓 틱이 와서 채워지니 못 느꼈지만, **장마감 후처럼 새 틱이 영영 안 오는 시간대엔 재접속해도 그 Sink가 영원히 비어있는 상태**로 남음.

   - 이 과정에서 "장마감 저장 로직 자체가 잘못된 것 아니냐"는 의심이 있었는데, H2 콘솔로 `STOCK` 테이블을 직접 확인한 결과 오늘(2026-07-29) 30건, 어제(2026-07-28) 30건이 정상적으로 저장돼 있었음 — 랭킹 장마감 저장(`saveStockEndData`) 로직 자체는 문제 없음을 확인하고 배제.

## 해결

1. **rate-limit 에러도 재시도 대상에 포함**: `KisService`에 `isRetryable(Throwable)` 헬퍼 추가, `PrematureChannelClosureException` 외에 메시지에 `EGW00201`이 포함된 경우도 재시도하도록 `getVolumeRank`/`getStockDetail`의 필터 조건 확장.
2. **로딩이 끝나도 데이터가 없으면 에러 화면 표시**: `stockDetail.html`, `stockHome.html` 둘 다 10초 안전장치 타이머를 "조용히 숨기기"에서 "데이터 도착 여부 체크 → 없으면 스피너 멈추고 `불러오지 못했습니다` + `다시 시도` 버튼 노출(클릭 시 `location.reload()`)"로 변경.
3. **(근본 수정) Sink를 연결 종료 시 더 이상 지우지 않음**: `getStockDetailStream()`의 `doOnCancel`/`doOnTerminate`에서 `detailSink.remove(code)` 제거. `replay().latest()` Sink가 마지막 값을 계속 들고 있다가 재접속 시 바로 재생(replay)해줌. 추가로 `doOnSubscribe` 시점에 `cachedStockData`에 값이 있으면 즉시 한 번 더 emit하는 안전장치도 넣음. (에러로 인한 재구성 시에는 기존대로 `onErrorResume`에서 Sink를 지우고 새로 만듦 — 이 경우는 정상적인 정리가 맞음)

관련 파일: `KisService.java` (`isRetryable`, `getStockDetailStream` 수정), `stockDetail.html`/`stockHome.html` + 각 css (로딩 에러 화면/재시도 버튼).

컴파일 확인 완료. Java 코드 변경이라 재시작 후 실제 재현 테스트 필요 (아직 안 함).
