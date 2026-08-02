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
