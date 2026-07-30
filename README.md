# 📈 한국투자증권 Open API 기반 모의투자 체결 시스템

## 📌 프로젝트 소개

한국투자증권 Open API를 활용하여 모의투자 환경에서 주식 조회 및 주문 체결을 수행하는 시스템입니다.  
실무에서 경험하지 못한 JPA/Hibernate 기반 객체지향 설계와 이벤트 기반 아키텍처를 직접 체득하기 위해 시작했으며,  
토큰 발급부터 시세 조회·주문·체결까지 실서비스에 가까운 흐름을 설계하고 AWS EC2에 배포하여 운영 경험까지 확보했습니다.

🔗 [모의 투자 링크 click!](http://mockst.duckdns.org:8080/stockHome)

---

## 🏗 시스템 아키텍처

![Architecture](https://github.com/ackdongClub97/StockTradeService/blob/main/architecture.svg)

## 🖥 화면 구성 (모바일 가능)

### 📊 메인 화면 (주식 랭킹, 주식 거래 내역 & 수익률)
![메인화면](https://github.com/user-attachments/assets/e4380f69-d8d6-424c-81fe-c6b47f20de3f)

### 📈 매수 / 매도
![매수매도1](https://github.com/user-attachments/assets/f9354310-a32b-4749-a583-6efda98ebb96)
![매수매도2](https://github.com/user-attachments/assets/8b697562-47f0-45d2-a302-2bfe178b4324)

### 🔐 로그인
![로그인](https://github.com/user-attachments/assets/6d09941b-c473-4b39-9285-e34ed8fe8534)

### 📝 회원가입
![회원가입](https://github.com/user-attachments/assets/7fedf9ff-962b-4dca-8c88-744eea0ee0cf)

---

## 🛠 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot, Spring Security (OAuth2 Client 포함) |
| ORM | JPA / Hibernate |
| Message Queue | Apache Kafka |
| HTTP 통신 | WebClient (비동기) |
| 실시간 통신 | WebSocket (KIS 실시간 체결가/호가 수신), SSE (브라우저 실시간 스트리밍) |
| 소셜 로그인 | Kakao OAuth2 |
| Database | H2 (File Mode) |
| Infra | AWS EC2 (Ubuntu) |
| Build | Gradle |

---

## 📂 프로젝트 구조

```
src/main/java
├── token        # 토큰 발급 및 관리
├── kis          # 한국투자증권 API 연동
├── stock        # 주식 시세 조회
├── order        # 주문/체결
├── matching     # 주문 매칭 엔진
├── member       # 회원 관리
└── security     # Spring Security 설정
```

---

## 🔄 주요 기능

### 1️⃣ 토큰 발급 및 자동 관리
- OAuth2 기반 Access Token 발급, API 호출 시 헤더에 자동 포함, 만료 시 자동 재발급 처리
- 실시간 웹소켓 접속을 위한 별도 승인키(approval_key)도 동일 주기(12시간)로 발급/갱신

### 2️⃣ 실시간 주식 시세
- **거래량 상위 종목 랭킹**: 정규장(09:00~15:30) 동안 REST로 폴링, 그 외 시간엔 KIS가 살아있는 데이터를 안 줘서 캐싱된 마지막(종가) 데이터를 재사용
- **개별 종목 체결가·호가 10단계**: 기존 REST 폴링 방식에서 **KIS 실시간 웹소켓**으로 전환 — 폴링 주기만큼의 지연 없이 실시간 반영
- SSE로 브라우저에 실시간 스트리밍(랭킹/체결가/호가/보유종목 각각 독립 스트림)

### 3️⃣ 자체 주문 매칭 엔진 (핵심 구현)
외부 증권사 API에 주문을 위임하지 않고, 체결 로직의 동작 원리를 구조적으로 이해하기 위해 직접 설계했습니다.

- **체결 원장(Trade) 분리**: 체결이 일어날 때마다 `Trade` 테이블에 INSERT(불변 원장), `Order`는 누적 체결수량/최근체결가/최종상태만 관리
- **호가 10단계 기반 실제 부분체결**: 지정가 조건에 맞는 매수/매도 호가 잔량만큼만 체결시키고, 모자란 만큼은 남겨서 다음 주기에 이어서 체결 — 여러 대기 주문이 겹쳐도 호가 잔량을 순서대로(시간 우선) 소진
- **주문 상태 머신**: `PENDING` → `PARTIAL` → `MATCHED` / `CANCELLED`
- **주문 생성 시점 잔고/보유수량 예약(reservation)**: 매수는 주문 즉시 예상 금액만큼 잔고를 선차감(잔고 부족 시 주문 자체가 거부됨), 매도는 보유수량 대비 검증. 취소 시 미체결분 예약 환급, 실제 체결가가 지정가보다 유리했으면 차액도 자동 환급
- **현재가/지정가 모드**: 매수·매도 모두 실시간가로 즉시 체결하거나 직접 단가를 지정 가능. **지정가 매수**는 그 가격보다 저렴한 매도호가가 있으면 자동으로 더 유리한 가격에 체결(가격개선)하되, 지정가 대비 5%를 초과해서 저렴한 호가는 개선 대상에서 제외 — 이 5% 밴드 제한은 지정가 전용 기능이며, **현재가 매수는 밴드 없이** 지정가(=주문 시점 현재가) 이하 호가면 그대로 체결
- **대기 주문 취소** 기능
- **Kafka 기반 비동기 처리**: 주문 요청 → Kafka Topic 발행 → MatchingService 소비 구조

### 4️⃣ `@Scheduled` 기반 재시도 구조
- 30초 주기 스케줄러로 `PENDING` / `PARTIAL` 상태 주문을 호가 기준으로 재매칭
- 주문 접수는 오전 8시부터 가능(대기), 실제 체결 시도는 정규장(09:00~15:30)에만 수행
- 미체결 주문의 누락 없는 처리 보장

### 5️⃣ 회원 관리
- Spring Security 기반 폼 로그인 + **카카오 소셜 로그인**(최초 로그인 시 자동 회원가입)
- 가입 시 초기 투자금 10,000,000원 지급
- **마이페이지**: 닉네임/전화번호 수정, 회원 탈퇴(탈퇴 계정은 재로그인 차단), 보유 종목·거래내역·**이번달 매매차익(기간별 그래프)** 실시간 조회

### 6️⃣ 보안
- KIS/네이버/카카오 API 키를 코드에서 완전히 분리 — 환경변수 또는 gitignore 처리된 로컬 시크릿 파일(`application-secret.yaml`)로만 주입

---

## 🔗 주문 처리 흐름

```
[1] 주문 요청 (매수/매도, 현재가/지정가)
   ↓
[2] 잔고(매수)/보유수량(매도) 검증 후 즉시 예약(차감) - 부족하면 주문 자체 거부
   ↓
[3] Kafka Topic 발행
   ↓
[4] MatchingService가 실시간 호가 10단계 기준으로 체결 가능 수량만큼 매칭
   ↓
[5] 체결분은 Trade 테이블에 INSERT, Order는 누적 상태 갱신 (PENDING → PARTIAL → MATCHED)
   ↓
[6] SSE로 실시간 체결 결과 전달 (보유종목/거래내역/매매차익도 함께 갱신)
   ↓
[7] 미체결 잔량은 PARTIAL 상태로 대기 → 30초 배치로 재시도 (또는 사용자가 직접 취소)
```

---

## 🚀 배포

- AWS EC2 (Ubuntu)
- Gradle 빌드 → JAR 패키징 → SCP 전송
- `application-prod.yml` 분리로 운영 환경 설정 관리
- `nohup java -jar` 백그라운드 실행

---

## 📌 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| SSE 비동기 dispatch 권한 오류 | Spring Security가 ASYNC dispatch 재검사 | `DispatcherType.ASYNC` permitAll 처리 |
| StaleObjectStateException | String ID에 `@GeneratedValue` 적용 | ID 생성 전략 수동 지정으로 변경 |
| 미체결 주문 체결 안됨 | 매칭 로직이 Kafka Consumer에 혼재 | `MatchingService` 분리 + 30초 배치 재시도 구조로 재설계 |
| 주문 API 인증 누락 | `permitAll` 범위 과도하게 설정 | 주문 엔드포인트 인증 필수로 수정 |
| 로그아웃이 동작 안 함 | Spring Security 로그아웃 필터는 POST만 매칭하는데 로그아웃 링크가 GET(`<a>`) | POST 폼으로 교체 |
| 웹소켓이 "message too big"(1009)으로 끊김 | 여러 종목 동시 구독 시 기본 텍스트 메시지 버퍼(8KB) 초과 | `WebSocketContainer` 버퍼 크기를 1MB로 확대 |
| 종목 상세페이지 새로고침 시 실시간 데이터가 영영 안 뜸 | SSE 재접속마다 캐시 Sink를 삭제 + 이미 구독된 종목은 웜업 재조회를 안 하는 구조라, 장마감 등 새 틱이 없는 시간엔 재접속해도 빈 화면 | Sink를 연결 종료 시 더 이상 삭제하지 않고 마지막 값을 재생(replay) |
| KIS 초당 호출 제한(EGW00201) 시 재시도 없이 실패 | 기존 재시도 필터가 연결 조기종료만 대상으로 함 | rate-limit 에러도 재시도 대상에 포함, 프론트에도 로딩 실패 시 에러 화면 + 재시도 버튼 추가 |
| 보유주식 실시간 스트림이 DB 커넥션 풀을 고갈시킴 | 가격 틱마다(초당) 캐싱 없이 DB 재조회 | 회원 단위 짧은 TTL 캐시 추가, 실제 체결 시에만 즉시 무효화 |

---

## ⚠️ 주의사항

- Access Token 만료 시 재발급 필요 (24h), 실시간 웹소켓 승인키도 동일하게 관리
- 요청 헤더 필수 값: `Authorization`, `appkey`, `appsecret`, `tr_id`
- 네이버 뉴스 API 사용을 위해 Client ID 및 Client Secret 키 필요, 카카오 로그인 사용을 위해 카카오 REST API 키/Client Secret 필요
- **모든 API 키는 코드에 직접 넣지 않고, 환경변수 또는 프로젝트 루트의 `application-secret.yaml`(gitignore 처리됨, `application-secret.yaml.example` 참고)로 주입**

---
