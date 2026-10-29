# 사용 기술 스택

이 프로젝트(한국투자증권 Open API 기반 모의투자 체결 시스템)에 사용된 기술을 **어디에 썼는지**와 **왜 골랐는지** 중심으로 정리한 문서.
수치는 실제 측정한 것만 적었고, 출처 문서(issue.md / README.md)를 함께 표시했다.

---

## 1. 언어 / 프레임워크

| 기술 | 어디에 썼나 | 선택 이유 / 비고 |
|---|---|---|
| **Java 21** | 전체 백엔드 | Gradle toolchain으로 버전 고정. 한 번 계산되면 바뀌지 않는 값(체결 계획 `FillPlan`, 큐 스냅샷 `QueuedOrder`)을 **record**로 표현해 불변성을 코드로 보장 |
| **Spring Boot 4.0.5** | 애플리케이션 기반 | 웹(MVC) + 리액티브 클라이언트(WebFlux) + JPA + Security + Kafka를 한 앱에서 함께 사용 |
| **Spring MVC** | 페이지/REST API, SSE 엔드포인트 | 주문·회원 API, `Flux`를 `text/event-stream`으로 반환하는 실시간 스트림 |
| **Spring WebFlux (WebClient)** | KIS·네이버 API 호출 (`KisService`) | 외부 API 호출 동안 톰캣 스레드를 블로킹하지 않기 위해. `Mono`/`Flux`/`Sinks`로 같은 시세 데이터를 pull·push 두 방식으로 제공 |
| **Reactor `Sinks`** | 랭킹 브로드캐스트(`replay().latest()`), 체결결과 멀티캐스트(`Sinks.Many<Order>`), 어드민 알림 | 데이터 하나를 여러 SSE 구독자에게 팬아웃 |
| **Spring `@Scheduled`** | 30초 매칭 배치, 장마감 자동취소, 토큰 12시간 갱신, 웹소켓 헬스체크, 랭킹 폴링 | 재시도 작업이 단순해 Spring Batch 같은 무거운 프레임워크 대신 선택(README에 "Spring Batch"라고 적혔던 건 실제론 `@Scheduled`) |
| **Lombok** | 엔티티/서비스 보일러플레이트 | `@Getter/@Setter`, `@RequiredArgsConstructor`, `@Slf4j` |

## 2. 데이터 / 영속성

| 기술 | 어디에 썼나 | 선택 이유 / 비고 |
|---|---|---|
| **JPA / Hibernate** | `Order`, `Trade`, `Member`, `Stock` 엔티티 | 객체지향 도메인 모델 설계를 직접 체득하기 위해 의도적으로 선택. 체결마다 `Trade`를 INSERT하는 불변 원장 + `Order`는 누적 상태만 관리 |
| **H2 (File Mode)** | 운영 DB (`~/stockTrade`), 테스트는 인메모리 | 별도 DB 서버 없이 단일 인스턴스에 배포 가능한 소규모 프로젝트용 선택. 한계: Loki 무한 재시도 로그로 디스크가 차면서 `.mv.db`가 두 번 손상된 사고가 있었음(CLAUDE.md 운영 규칙) |
| **`@Transactional`** | 주문 생성(잔고 예약+주문 저장), 취소(환급+상태), 장마감 자동취소 | 잔고 변경과 주문 상태 저장이 따로 커밋되면 "돈만 빠지고 주문은 없는" 상태가 생기던 문제 해결. 같은 클래스 안 호출은 `@Lazy` 자기주입으로 프록시를 거치게 함 (issue.md) |
| **HikariCP** | 커넥션 풀(Spring Boot 기본) | 부하 시 풀 고갈 이슈를 추적·기록 (issue.md) |

## 3. 메시징 / 실시간 통신

| 기술 | 어디에 썼나 | 선택 이유 / 비고 |
|---|---|---|
| **Apache Kafka** | 토픽 `stock-order`(주문 → 즉시체결), `stock-order-result` | 주문 접수(빠른 응답)와 체결 처리(무거운 매칭)를 분리해, 매칭이 늦어져도 주문 API 응답이 막히지 않게 하기 위해. 현재 한계: 메시지 키가 `orderId`라 종목 단위 순서 보장 없음, 실패 시 DLQ 없음, DB와 Kafka 이중 쓰기 |
| **WebSocket (KIS 실시간 피드)** | `KisWebSocketService` — 호가 10단계(`H0STASP0`) 구독 | REST 폴링은 주기만큼 구조적으로 지연이 생겨서 도입. 공유 연결 1개, `PINGPONG` 에코, 30초 헬스체크 재연결. 체결가 구독을 빼고 호가만 구독해 **종목당 등록 슬롯 2건 → 1건**으로 줄여 40종목 한도 안에서 지원 종목 수 확대 (issue.md) |
| **SSE (Server-Sent Events)** | 랭킹 / 종목 시세·호가 / 체결 결과 / 어드민 알림 스트림 | 서버→브라우저 단방향 전달엔 WebSocket보다 가벼운 HTTP 기반으로 충분하고, 재연결을 브라우저가 처리. Spring Security에서 `DispatcherType.ASYNC` 허용이 필요했던 사고 기록 있음 |

## 4. 매칭 엔진 / 동시성 (직접 구현)

| 기술 | 어디에 썼나 | 선택 이유 / 비고 |
|---|---|---|
| **`PriorityBlockingQueue` (Heap)** | 종목별 매수/매도 호가창 `OrderBook` | 가격-시간 우선순위를 삽입 시 O(log n)으로 유지. 취소/체결 건은 꺼낼 때 DB 상태로 걸러내는 lazy deletion. 재시작 시 DB에서 재구성 |
| **`findAllById` 배치 조회** | `drainAndExecute` | 건당 `findById`(N+1)를 1회 조회로 변경 — **(실측) Old GC 41회 → 7회(-83%)**, Young GC -18.6% (issue.md) |
| **`synchronized` + `ConcurrentHashMap`** | 종목별 `ConsumptionState`(같은 호가 틱의 잔량 공유 + 종목 락) | 즉시체결과 배치가 같은 호가 잔량을 중복 체결하던 레이스 컨디션 해결. 2026-10-29 락 밖 조회로 인한 이중 체결/취소 주문 체결 버그도 락 안 재조회로 수정 (issue.md). 한계: 단일 인스턴스 전제 |
| **`ConcurrentHashMap` + `Deque`** | FDS 회원별 슬라이딩 윈도우 | 규칙 3종 수준이라 Kafka Streams 같은 스트림 처리 프레임워크 없이 순수 Java 컬렉션으로 충분하다고 판단 |

## 5. 보안 / 인증

| 기술 | 어디에 썼나 | 선택 이유 / 비고 |
|---|---|---|
| **Spring Security** | 세션 기반 폼 로그인, 공개/인증 경로 설정 | 표준 보안 프레임워크. 역할 체계 없이 어드민은 회원 ID 비교로 제한 |
| **OAuth2 Client (카카오 로그인)** | `KakaoOAuth2UserService`, 최초 로그인 시 자동 회원가입 | 커스텀 인증 필터 없이 붙일 수 있고, 국내 서비스에서 가장 보편적인 소셜 로그인 |
| **BCrypt** | 비밀번호 해싱 (`PasswordConfig`) | 순환 의존 때문에 `SecurityConfig`에서 분리. 부하 테스트 JFR에서 **(실측) CPU 샘플의 31.2%** 차지, 다만 낮춰도 전체 CPU/지연은 개선 안 됨 (issue.md) |
| **시크릿 분리** | 환경변수 / gitignore된 `application-secret.yaml` | KIS·네이버·카카오·Anthropic 키를 코드에서 완전히 분리 |

## 6. 외부 API

| 기술 | 어디에 썼나 | 비고 |
|---|---|---|
| **한국투자증권(KIS) Open API** | 토큰, 거래량 순위(REST 폴링), 시세/호가(웹소켓) | 모의투자 엔드포인트 사용. `PrematureChannelClosureException`만 백오프 3회 재시도 |
| **네이버 뉴스 검색 API** | 종목 관련 뉴스 | `news` 패키지 |
| **Anthropic Claude API** (`anthropic-java`) | 뉴스 영향 분석 (`NewsAnalysisService`) | 구조화 출력으로 타입 있는 객체를 바로 받기 위해 선택. 종목·날짜 단위 캐싱으로 하루 1회만 호출 |

## 7. 프론트엔드

| 기술 | 어디에 썼나 | 비고 |
|---|---|---|
| **Thymeleaf** (+ `thymeleaf-extras-springsecurity6`) | 서버 렌더링 페이지 (홈, 종목 상세, 마이페이지, 어드민) | |
| **Vanilla JS + `EventSource`** | 실시간 시세/체결 알림 수신 | 별도 프론트 프레임워크 없음 |

## 8. 관측 / 운영

| 기술 | 어디에 썼나 | 선택 이유 / 비고 |
|---|---|---|
| **Grafana Loki** + `loki-logback-appender` | 앱 로그 직접 전송, 어드민 화면 "로그 보기" | Promtail 없이 Logback 어펜더로 직접 전송. `LOKI_URL` 미설정 시 꺼지도록 janino `<if>`로 게이팅(EC2 디스크 사고 이후) |
| **Grafana** | 로그/메트릭 조회 | `observability/docker-compose.yml` |
| **Prometheus** | k6 부하테스트 결과 수집(remote write) | 로컬 관측용 |
| **Docker Compose** | Loki + Prometheus + Grafana 로컬 구성 | |
| **JFR (Java Flight Recorder)** | 부하 테스트 중 CPU 병목 프로파일링 | BCrypt·H2 MVStore·GC 비중 분석에 사용 (issue.md) |

## 9. 테스트 / 성능 검증

| 기술 | 어디에 썼나 | 비고 |
|---|---|---|
| **JUnit 5 + Mockito** | `MatchingServiceTest`(13건), `FraudDetectionServiceTest`(8건) | 경쟁 상황은 `CountDownLatch`, 스레드 `BLOCKED` 상태 대기로 순서를 고정해 재현. 수정 전 코드로 되돌려 테스트가 실제로 실패하는지까지 확인 |
| **`@DataJpaTest`** | `MatchingServiceIntegrationTest`(3건), 매칭 알고리즘 벤치마크(일회성, 삭제됨) | 실제 Hibernate + H2 인메모리. `Propagation.NOT_SUPPORTED`로 운영처럼 호출마다 커밋 |
| **k6** | `loadtest/k6` — 500~2000명 부하 테스트 | `noCookiesReset` 등 설정 관련 사고 기록 있음 (issue.md) |

## 10. 빌드 / 배포

| 기술 | 어디에 썼나 | 비고 |
|---|---|---|
| **Gradle** | 빌드, 테스트, JAR 패키징 | |
| **AWS EC2 (Ubuntu)** | 단일 인스턴스에 앱 + Kafka/Zookeeper 함께 구동 | JAR를 SCP로 전송 후 `prod` 프로파일로 재기동 (README) |
