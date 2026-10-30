# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 저장소에서 작업할 때 참고하는 안내 문서입니다.

## 프로젝트 개요

한국투자증권(KIS) Open API 기반 모의 주식 거래 시스템. 회원가입 시 가상 투자금(10,000,000원)을 받고, KIS에서 프록시한 실시간 거래량 순위·시세를 보며 매수/매도 주문을 넣으면, 자체 구현한 주문 매칭 엔진이 KIS 실시간 시세를 기준으로 체결시킨다(외부 증권사에 주문을 위임하지 않음 — 매칭은 이 앱 안에서 구현).

## 명령어

빌드 및 실행 (Gradle wrapper, Java 21 toolchain):

```bash
./gradlew build              # 컴파일 + 테스트 + 패키징
./gradlew bootRun            # 로컬 실행 (src/main/resources/application.yaml 사용)
./gradlew test               # 전체 테스트
./gradlew test --tests stockOrder.stockTrade.StockTradeApplicationTests   # 테스트 클래스 하나만 실행
```

앱이 떠 있다고 가정하는 로컬 인프라:
- Kafka 브로커 `localhost:9092` (토픽 `stock-order`, `stock-order-result` — 자동 생성 설정이 없으므로 브로커/토픽이 미리 있어야 함).
- MySQL 8.4: 저장소 루트 `docker-compose.yml`(`docker compose up -d mysql`), **호스트 포트 3307** — 3306은 이 PC에 직접 설치된 MySQL이 사용 중. `spring.jpa.hibernate.ddl-auto: update`. 2026-10-30에 H2에서 전환했고 H2 의존성은 제거됨(예전 `~/stockTrade.mv.db` 파일은 이전하지 않고 남겨둠). 운영(EC2) 배포는 당분간 하지 않으므로 `prod` 프로파일은 신경 쓰지 않는다.

서버 여러 대(로컬 클러스터):
- `./gradlew bootJar && docker compose --profile cluster up -d --build` → nginx(`localhost:8088`, 라운드로빈) → `app1`(시세 수집기, `MARKET_DATA_COLLECTOR=true`) / `app2` / `app3`, 클러스터 전용 Kafka(호스트에서 `localhost:9095`), MySQL(3307). `application-secret.yaml`은 `/app`으로 마운트. 중지: `docker compose --profile cluster stop`.

테스트:
- **`./gradlew test`는 Docker가 실행 중이어야 한다.** 통합 테스트(`@DataJpaTest`, `@SpringBootTest`)는 `support/MySqlContainerConfig`(Testcontainers `mysql:8.4` + `@ServiceConnection`)로 MySQL 컨테이너를 띄워서 돈다 — 운영 파일 DB나 실제 KIS API를 건드리지 않는다.
- `StockTradeApplicationTests`는 `KisService`/`KisWebSocketService`를 `@MockitoBean`으로 막고 Kafka 리스너 자동 시작을 꺼서 외부 연동 없이 기동만 확인한다. 실제 KIS를 호출하면 연속 실행 시 토큰 발급 제한에 걸려 실패했었음.

이 저장소에는 설정된 린터가 없다.

## 아키텍처

### 패키지 구성 (`src/main/java/stockOrder/stockTrade/`)
- `token` — KIS OAuth2 액세스 토큰 발급/보관.
- `kis` — `KisService`가 외부 KIS Open API로 나가는 유일한 관문(WebClient 기반, 리액티브). 서버 여러 대일 땐 수집기 서버만 실제로 KIS를 호출하고 `MarketDataBus`로 시세를 나눔(아래 "서버 여러 대 구조"). 순위 데이터와 종목별 시세 데이터의 인메모리 캐시를 소유하고, 같은 데이터를 pull(`Mono`)과 push(`Flux`/`Sinks`) 두 방식으로 모두 제공.
- `stock` — MySQL에 저장하는 장마감 순위 스냅샷 JPA 엔티티/리포지토리.
- `member` — Spring Security `UserDetailsService` 연동(`CustomerDetails`/`CustomerDetailsService`) + 회원 CRUD, 투자금 잔고. `CustomerDetails`가 `UserDetails`와 `OAuth2User`를 모두 구현해서, 폼 로그인(`CustomerDetailsService`)이든 카카오 OAuth2(`KakaoOAuth2UserService`)든 `@AuthenticationPrincipal CustomerDetails`가 똑같이 동작한다. 카카오 로그인은 최초 로그인 시 `Member`를 자동 생성(`memberId = "kakao_" + kakaoId`, `provider = "KAKAO"`, 초기 투자금/레벨은 일반 가입과 동일) — `SecurityConfig.oauth2Login` 참고.
- `order` — 주문 엔티티/리포지토리/컨트롤러/서비스. 주문 ID는 `ORD{yyyyMMdd}{seq4}` 형식으로 생성(`OrderController.submitOrder` 참고).
- `matching` — 자체 구현 주문 매칭/체결 엔진 `MatchingService` (아래 참고).
- `news` — 종목 관련 뉴스를 네이버 뉴스 검색 API로 프록시.
- `security` — `SecurityConfig`, 폼 로그인, 공개 페이지/API의 permitAll 경로.
- `view` — `PageController`, Thymeleaf 페이지 라우팅(`/stockHome`, `/stock/{code}`, `/login`, `/join`, `/myPage`).
- `admin` — 미체결 주문 운영/모니터링 (아래 "주문 모니터링 / 어드민" 참고).
- `outbox` — Transactional Outbox(`OutboxEvent`, `OutboxRelay`)와 DLQ 적재/재발행(`DeadLetter`, `DeadLetterService`).

### 주문 흐름 (주문/매칭 코드를 건드리기 전에 반드시 이해해야 하는 핵심)
1. `POST /api/order/create`(`OrderController`) → `OrderCommandService.submit` 트랜잭션: 회원 행 비관적 락 → 잔고(매수)/보유수량(매도) 확인 → 매수는 잔고 예약 차감 → 주문 `PENDING` 저장 → **Outbox(`outbox_events`)에 `stock-order` 메시지 기록**. 커밋 후 컨트롤러가 `OrderBook` 등록 + `OutboxRelay.publishForAggregate`로 즉시 발행(실패 시 `OutboxRelay.publishPending`이 1초마다 재시도, 행 락으로 이중 발행 방지).
2. `OrderService.processOrder`(`stock-order`의 `@KafkaListener`)가 소비해서 `MatchingService.tryMatch`로 즉시체결을 시도. 예외는 삼키지 않음 — `OrderKafkaConfig`의 `DefaultErrorHandler`가 재시도 2번 후 `stock-order.DLT`로 보내고(JSON 파싱 실패는 즉시), `DeadLetterService`가 `dead_letters`에 적재 + 어드민 알림, `POST /api/admin/dead-letters/{id}/replay`로 재발행. 메시지는 최소 한 번 전달이라 중복이 올 수 있음 — `tryMatch`의 락 안 상태 재확인, `Order @Version`, `OrderBook` 멱등 등록으로 중복에 안전.
3. 즉시 체결되지 않으면 `MatchingService.matching()`의 `@Scheduled` 배치가 30초마다(`KisService.isMarketOpen()`일 때만) 돌면서 `PENDING`/`PARTIAL` 주문이 있는 종목코드를 모두 가져와 종목별 현재 시세를 조회하고, 가격-시간 우선순위대로 체결 가능한 주문을 매칭한다: `OrderRepository.findPendingBuyOrders`/`findPendingSellOrders`가 각각 `price DESC`/`price ASC`로 정렬(더 공격적인 지정가가 먼저 — 매수는 높을수록, 매도는 낮을수록), 같으면 `createdAt ASC`. README에는 "Spring Batch"라고 적혀 있지만 실제로는 평범한 `@Scheduled` 메서드다 — 프로젝트에 Spring Batch 의존성은 없다.
4. 주문 상태 머신: `PENDING` → `MATCHED` / `PARTIAL` / `CANCELLED` / `FAILED`. `matchedQuantity`/`matchedPrice`는 주문의 요청 `quantity`/`price`와 별도로 관리.
5. 체결 결과는 SSE로 주문한 회원에게 푸시: `MatchingService`가 멀티캐스트 `Sinks.Many<Order>`를 들고 있고, `OrderController.streamOrderResult`(`GET /api/order/stream`)에서 회원별로 필터링.
6. 잔고: 매수는 **주문 접수 시점**에 지정가 × 수량을 예약 차감하고, 체결 시엔 `MatchingService.updateSeed`가 실제 체결가와의 차액만 환급, 취소 시엔 미체결분 환급. 잔고 증감은 모두 `MemberRepository.addSeed`(`seed = seed + delta` 원자적 UPDATE). 체결(`execute`)은 주문·Trade·잔고를 한 트랜잭션으로 묶고, 취소/체결 경쟁은 `Order @Version`으로 나중 쪽이 롤백(취소가 지면 409). 락 순서는 항상 주문 행 → 회원 행(데드락 방지).

### 서버 여러 대 구조 (2026-10-30)
- **체결 = 종목 파티션 단위 단일 writer**: `stock-order` 키가 종목코드(파티션 6개). 컨슈머 그룹(`order-group`)에서 파티션을 받은 서버만 그 종목을 체결한다(`StockOwnership`, `OrderService`의 `ConsumerSeekAware` 콜백 → `MatchingService.onPartitionsAssigned/Revoked`). 30초 배치·장마감 취소도 `ownership.owns(stockCode)`로 자기 종목만. 파티션 반납 시 소유권 해제 → 종목 락을 잡아 진행 중 체결 완료 대기 → 호가창 정리. `CooperativeStickyAssignor`. 호가창 등록은 컨트롤러가 아니라 담당 서버 컨슈머(`processOrder`)가 한다.
- **시세 = 수집기 1대**: `market-data.collector=true`(env `MARKET_DATA_COLLECTOR`, 기본 true) 서버만 KIS 토큰·웹소켓·REST·랭킹 폴링·장마감 랭킹 저장을 하고 `MarketDataBus`로 Kafka에 발행. 모든 서버는 compacted 토픽(`market-asking-price`/`market-stock-detail`/`market-ranking`, 키=종목코드)을 고유 그룹 + earliest로 받아 캐시·SSE에 반영 → 장 마감 후 기동해도 종가 표시. 종목 구독 요청은 `market-subscribe`(compacted)로 수집기에 전달. 수집기가 아니면 `getStockDetail`/`getVolumeRank`가 KIS 대신 Kafka로 받은 값을 반환.
- **세션·알림**: Spring Session JDBC(MySQL `SPRING_SESSION`, 그래서 `Member`는 `Serializable`). 체결 결과(`order-result`, `OrderResultBroadcaster`)·관리자 알림(`admin-alert`)은 Kafka 브로드캐스트(고유 그룹 + latest).
- 정규장(09:00–15:30)에만 체결하고 그 외 시간엔 종가를 보여준다는 규칙은 그대로(`isMarketOpen()`).

### KIS API 연동 (`KisService` + `KisWebSocketService`)
- REST: 공용 bearer 토큰 하나를 `@Scheduled`(`TokenService.fetchToken`)로 12시간마다 갱신. KIS 토큰 만료는 24시간.
- 순위 데이터(`/quotations/volume-rank`)는 여전히 설정 가능한 주기(`kis.rank.refresh-interval-ms`, 기본 30초)로 REST 폴링하고, `replay().latest()` `Sink`로 모든 구독자에게 뿌린다. `isMarketOpen()`/`isAfterMarket()`으로 장전/장중/시간외/장마감을 구분(Asia/Seoul 기준, 09:00–15:30 정규장, 15:30–18:00 시간외). KIS에는 복합 순위 목록의 실시간 푸시가 없어서 이 부분은 REST로 유지.
- 장마감 시점에 현재 순위 스냅샷을 하루 한 번 `stock`에 저장(`saveStockEndData`, `saveToday` 플래그로 가드)하고 장 이후엔 그 캐시로 응답. 앱 재시작 시 DB에서 다시 로드(`restartFromDb`)해서 재배포해도 순위 데이터가 유지된다.
- **종목별 시세/호가는 REST 폴링이 아니라 실시간 웹소켓이다.** `KisWebSocketService`가 KIS 실시간 피드(`hantu-openapi.wsUrl`)에 공유 연결 하나를 유지하며, 별도 `approval_key`(`TokenService.fetchApprovalKey`, 역시 12시간마다 갱신되며 갱신 시 재연결)로 인증. `subscribe(code)`는 멱등이며 해당 종목에 `H0STCNT0`(체결가)와 `H0STASP0`(호가 10단계)를 둘 다 등록하고, 첫 틱이 오기 전 화면이 비지 않도록 REST로 한 번 미리 조회한다. `KisService.getStockDetailStream`의 `doOnSubscribe`(종목 상세 페이지 SSE)와 `MatchingService`(아래 참고)에서 호출되며 명시적으로 구독 해제하지 않는다(`MAX_SUBSCRIPTIONS = 40` 상한 — 넘는 종목은 REST로 대체되므로 깨지진 않고 실시간이 아니게 될 뿐). 수신 프레임은 파이프/캐럿 구분(`0|TR_ID|count|f1^f2^...`)이고 `tr_id`로 라우팅. `PINGPONG` 제어 프레임은 그대로 되돌려 보내야 하며, 안 그러면 KIS가 연결을 끊는다. 30초 `@Scheduled` 헬스체크가 세션이 끊기면 재연결(및 전체 재구독)한다.
- `KisService.pushStockDetail(code, dto)`가 기존 `cachedStockData`/`detailSink`의 쓰기 쪽 — `KisWebSocketService`가 예전 15초 폴링 루프(제거됨) 대신 `H0STCNT0` 틱마다 이를 호출한다. `KisService`는 `KisWebSocketService`를 `@Lazy`로 주입받는데(반대 방향은 일반 생성자 의존성), 두 빈 사이의 순환 의존을 끊기 위한 것이다.
- `MatchingService.matching()`/`tryMatch()`는 먼저 `kisService.getCachedStockData(code)`(웹소켓이 채움)를 읽고, 캐시가 비었을 때(새 종목, 아직 틱 없음)만 REST로 대체 — 상세 페이지와 같은 "호출하면서 구독 보장" 패턴.
- 두 KIS REST 호출 모두 `PrematureChannelClosureException`일 때만 백오프로 최대 3회 재시도. 다른 에러는 그대로 전파되고 KIS 원본 에러 바디와 함께 로깅된다.

### 주문 모니터링 / 어드민 (`admin` 패키지)
- `Order`에 `lastUnmatchedReason`(`UnmatchReason`: `NO_ELIGIBLE_PRICE` / `NO_ORDERBOOK_DATA` / `SYSTEM_ERROR`)과 `lastAttemptAt`이 있고, `MatchingService`의 모든 매칭 시도(`matching()`, `tryMatch()`, `execute()`)에서 갱신된다. 그래서 멈춰 있는 `PENDING`/`PARTIAL` 주문의 원인을 로그에서만이 아니라 조회로 확인할 수 있다. `execute()`와 `matching()`의 종목코드별 루프는 try/catch로 감싸져 있어서, 주문/종목 하나에서 예외(주로 DB/HikariCP 문제)가 나도 배치의 나머지가 중단되지 않는다 — 그런 건은 `SYSTEM_ERROR`로 표시되고 `AdminAlertService`의 SSE sink로 발행된다.
- `GET /admin/orders`(Thymeleaf, `adminOrders.html`)는 모든 `PENDING`/`PARTIAL` 주문과 사유를 보여주고, "로그 보기" 버튼이 Loki를 직접 조회(`LokiClient`, `GET /api/admin/logs`)해서 `lastAttemptAt` 전후의 해당 주문 로그를 보여준다 — 이 화면에선 Grafana가 필요 없지만 Grafana Explore로도 볼 수 있다.
- `/admin/**`, `/api/admin/**` 접근은 `AdminController` 안에서 로그인한 회원 ID를 `admin.member-id`(환경변수 `ADMIN_MEMBER_ID`, 기본값 빈 값 = 아무도 접근 불가)와 비교해서 막는다 — 이 앱엔 역할/권한 체계가 없으므로(`CustomerDetails.getAuthorities()`는 항상 `ROLE_USER`) 여기서 `hasRole(...)`을 쓰지 말 것.
- 이를 위한 로컬 로그 파이프라인: `observability/docker-compose.yml`이 Loki + Grafana를 띄운다(해당 디렉터리에서 `docker compose up -d`). 앱은 Logback 어펜더(`logback-spring.xml`, `loki-logback-appender`)로 Loki에 로그를 직접 전송 — Promtail/파일 tailing 없음. `observability/export-logs.sh`는 Loki HTTP API로 특정 시간 범위를 일괄 내보내 오프라인 분석에 쓴다.
- **Loki 로깅을 켜려면 `LOKI_URL`을 반드시 명시적으로 설정해야 한다 — 더 이상 `localhost`가 기본값이 아니다.** `logback-spring.xml`이 `LOKI` 어펜더(와 그 `<appender-ref>`)를 `<if condition='!"".equals(property("LOKI_URL"))'>`로 감싸고 있으며, 이를 위해 런타임 클래스패스에 `org.codehaus.janino:janino`가 필요하다. `LOKI_URL`이 없으면 조용히 `localhost:3100`을 가리키는 대신 Loki 로깅이 완전히 꺼진다.

### 보안
- 세션 기반 폼 로그인 + 카카오 OAuth2 로그인(`SecurityConfig`), CSRF 비활성화, frame options는 same-origin(예전 H2 콘솔용 설정이 남아 있음).
- `BCryptPasswordEncoder` 빈은 별도 `@Configuration` 클래스인 `PasswordConfig`가 소유한다 — 원래 `SecurityConfig`에 있었지만, `SecurityConfig`가 `KakaoOAuth2UserService`에 의존하고 그게 다시 인코더에 의존해서 `SecurityConfig`가 자기 자신에 의존하는 순환이 생겼기 때문. 이 순환이 다시 생기지 않도록 인코더 빈을 `SecurityConfig`(또는 `SecurityConfig`가 의존하는 클래스)에 두지 말 것.
- 공개(인증 불필요) 경로: `/login`, `/join`, 정적 리소스, `/stockHome`, `/stock/**`, `/api/member/**`, `/api/rank/**`, `/api/news/**`, `/api/stock/**`, `/api/volume/**`, `/api/sse/**`, `/oauth2/**`, `/login/oauth2/**`. 나머지(`/api/order/**` 포함)는 모두 인증 필요.
- SSE 엔드포인트는 `DispatcherType.ASYNC`를 명시적으로 허용해야 한다 — Spring Security가 비동기 디스패치에서 인증을 다시 검사하기 때문에, 이걸 빠뜨리면 SSE 스트림이 403이 난다(README에 기록된 과거 사고).

### 설정
- 설정은 `src/main/resources/application.yaml` 하나이고 로컬 실행 기준이다. 운영(EC2) 배포는 하지 않으므로 `prod` 프로파일·`application-prod.yml`은 없다(2026-10-30 제거). 서버 여러 대 실행은 docker-compose 환경변수로 덮어쓴다.
- `hantu-openapi.appUrl`은 KIS **모의투자** 엔드포인트(`openapivts...`)를 가리킨다. 실전 엔드포인트는 주석 처리된 채로 남아 있다.
- KIS, 네이버, 카카오 인증정보(`HANTU_APP_KEY`, `HANTU_APP_SECRET`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`)는 환경변수 또는 저장소 루트의 gitignore된 선택적 파일 `application-secret.yaml`(`application-secret.yaml.example` 참고)에서 읽으며, **커밋하지 않는다**. 실제 인증정보를 `application.yaml`에 다시 하드코딩하지 말 것.

## 문서 작성 가이드
- **이 프로젝트의 모든 문서(CLAUDE.md, README.md, issue.md, version-update.md, 기타 새로 만드는 문서)와 코드 주석은 한국어로 작성한다.** 코드 식별자, 명령어, 설정 키, 로그/에러 원문은 원래 표기를 그대로 둔다.
- README.md나 issue.md를 갱신할 때 처리량/지연시간/성능 수치는 실제 측정(부하 테스트 실행, 실제 로그, 벤치마크)에서 나온 것만 넣는다. 지표 섹션을 채우려고 수치를 지어내거나 추정하지 말 것 — 측정한 게 없으면 빼둔다.

## 운영 규칙 (사고에서 배운 것)
- **외부 도구/라이브러리의 동작을 커스텀 코드로 우회하기 전에, 공식 문서/검색으로 이미 있는 설정 옵션부터 확인한다.** 내장 옵션으로 해결되지 않는다는 걸 확인한 뒤에만 코드 레벨 우회로 넘어간다. 선례: k6 세션 쿠키 이슈("http_req_failed 18%대" 이슈 참고)에서 k6가 VU iteration 사이에 쿠키 저장소를 초기화한다는 진단은 맞았지만, k6에 이미 해당 설정이 있는지 확인하지 않고 로그인 쿠키를 변수에 저장해 매 요청마다 `Cookie` 헤더로 붙이는 수동 우회로 고쳤다. 실제로는 `options.noCookiesReset: true`로 iteration 간 쿠키를 그대로 유지할 수 있다. 수동 우회도 동작은 하지만, 설정 한 줄로 끝날 문제를 코드로 풀고 있었던 것(2026-08-25 재검토에서 발견, 스크립트는 아직 수정 안 함).
- **외부 연동 클라이언트/어펜더가 설정값이 없을 때 그럴듯한 엔드포인트(예: `localhost`)로 조용히 기본 연결하게 두지 않는다.** 명시적으로 켜야만 동작하게 한다 — 설정값이 없으면 연동을 끄고, 주소를 추측하지 말 것. 선례: Loki Logback 어펜더가 예전엔 `LOKI_URL` 기본값을 `http://localhost:3100/...`으로 잡고 있었는데, Loki가 없는 EC2에 배포할 때마다 전체 스택트레이스를 찍으며 무한 재시도하는 로그가 디스크를 채웠고, 두 번이나 쓰기 도중 H2 `.mv.db` 파일을 손상시켰다 — 한 번은 회원가입 전체가 멈췄고, 한 번은 Kafka/Zookeeper 상태까지 함께 손상됐다. `logback-spring.xml`에서 어펜더를 `<if condition='!"".equals(property("LOKI_URL"))'>`로 감싸서 해결(커밋 `2d3f752`). 앞으로 외부/선택적 서비스(메트릭, 트레이싱, 웹훅 등)용 클라이언트에도 같은 원칙을 적용할 것 — 설정값이 없으면 "꺼짐"이어야지, "localhost로 시도하고 영원히 재시도"가 되면 안 된다.
- **사용자가 질문하면 그 질문에 먼저, 바로 답한다.** 무엇을 묻는지 확실하지 않으면 추측하지 말고 먼저 되묻는다. 답 대신 설명, 목록, 주변 정보로 대체하지 말 것. 사용자가 같은 걸 다시 물으면 놓쳤다는 신호로 받아들인다: 멈추고, 직답을 주거나 되묻는다. 답한 뒤에는 실제로 물은 것과 근거 자료에 맞는지 확인하고 보낸다. 선례: 매칭엔진 힙 정리 스레드(2026-08-31)에서 assistant가 같은 결론을 네 번이나 바꿔 말했고, "무슨 비용이야?"라는 질문에 한 줄 답("개선 전 = 정렬로 인한 CPU 시간, 개선 후 = 힙 poll 비용") 대신 여러 항목으로 쪼갠 설명만 계속 내놓아서, 결국 사용자가 직접 답을 말해야 했다. 메모리 `feedback_grasp_question_then_verify_output`에도 기록됨.
