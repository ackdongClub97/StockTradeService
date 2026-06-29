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
| Framework | Spring Boot, Spring Security, Spring Batch |
| ORM | JPA / Hibernate |
| Message Queue | Apache Kafka |
| HTTP 통신 | WebClient (비동기) |
| 실시간 통신 | SSE (Server-Sent Events) |
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
- OAuth2 기반 Access Token 발급
- API 호출 시 헤더에 자동 포함
- 만료 시 자동 재발급 처리

### 2️⃣ 실시간 주식 시세 조회
- 거래량 상위 종목 랭킹 조회
- 등락률, 거래량 등 실시간 데이터 제공
- SSE 기반 실시간 스트리밍

### 3️⃣ 자체 주문 매칭 엔진 (핵심 구현)
외부 증권사 API에 주문을 위임하지 않고, 체결 로직의 동작 원리를 구조적으로 이해하기 위해 직접 설계했습니다.

- **주문 상태 머신**: `PENDING` → `MATCHED` / `PARTIAL` / `CANCELLED` / `FAILED`
- **부분체결(PARTIAL) 처리**: 체결 수량과 잔여 수량을 분리 관리하며, `matched_price`를 주문 `price`와 별도 저장하여 복잡한 금융 데이터 정합성 확보
- **자동 재시도**: 잔여 수량은 `PENDING` 상태로 유지되어 자동 재매칭 대기
- **KIS API 현재가 기반 매칭** 및 시간 우선 원칙 적용
- **장 마감 후 주문**: 캐싱된 종가 기준으로 처리
- **Kafka 기반 비동기 처리**: 주문 요청 → Kafka Topic 발행 → MatchingService 소비 구조

### 4️⃣ Spring Batch 기반 재시도 구조
- 30초 주기 배치 스케줄러로 `PENDING` / `PARTIAL` 상태 주문을 자동 재처리
- 미체결 주문의 누락 없는 처리 보장

### 5️⃣ 회원 관리
- Spring Security 기반 인증/인가
- 가입 시 초기 투자금 10,000,000원 지급
- 보유 주식 및 거래 내역 조회

---

## 🔗 주문 처리 흐름

```
[1] 주문 요청 (매수/매도)
   ↓
[2] Kafka Topic 발행
   ↓
[3] MatchingService 소비 및 매칭 처리
   ↓
[4] 주문 상태 업데이트 (PENDING → MATCHED/PARTIAL/CANCELLED)
   ↓
[5] SSE로 실시간 체결 결과 전달
   ↓
[6] 미체결 시 → 30초 배치 재시도
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

---

## ⚠️ 주의사항

- Access Token 만료 시 재발급 필요 (24h)
- 요청 헤더 필수 값: `Authorization`, `appkey`, `appsecret`, `tr_id`
- 네이버 뉴스 API 사용을 위해 Client ID 및 Client Secret 키 필요

---
