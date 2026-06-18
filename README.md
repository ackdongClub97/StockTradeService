# 📈 한국투자증권 Open API 기반 모의투자 체결 시스템

## 📌 프로젝트 소개
한국투자증권 Open API를 활용하여 모의투자 환경에서 주식 조회 및 주문 체결을 수행하는 시스템입니다.  
실무에서 경험하지 못한 JPA/Hibernate 기반 객체지향 설계와 이벤트 기반 아키텍처를 직접 체득하기 위해 시작했으며,  
토큰 발급부터 시세 조회·주문·체결까지 실서비스에 가까운 흐름을 설계하고 AWS EC2에 배포하여 운영 경험까지 확보했습니다.

🔗 [배포 주소](http://mockst.duckdns.org:8080/stockHome)

## 🖥 화면 구성 (모바일 가능)

### 📊 메인 화면 (주식 랭킹, 주식 거래 내역 & 수익률)
<img width="1170" height="2140" alt="IMG_8533" src="https://github.com/user-attachments/assets/e4380f69-d8d6-424c-81fe-c6b47f20de3f" />


### 📈 매수 / 매도
<img width="1170" height="2102" alt="IMG_8534" src="https://github.com/user-attachments/assets/f9354310-a32b-4749-a583-6efda98ebb96" />
<img width="1170" height="1540" alt="IMG_8535" src="https://github.com/user-attachments/assets/8b697562-47f0-45d2-a302-2bfe178b4324" />


### 🔐 로그인
<img width="1170" height="2051" alt="IMG_8531" src="https://github.com/user-attachments/assets/6d09941b-c473-4b39-9285-e34ed8fe8534" />


### 📝 회원가입
<img width="1170" height="2121" alt="IMG_8532" src="https://github.com/user-attachments/assets/7fedf9ff-962b-4dca-8c88-744eea0ee0cf" />


---

## 🛠 기술 스택

- **Language**: Java 21
- **Framework**: Spring Boot
- **ORM**: JPA / Hibernate
- **Message Queue**: Apache Kafka
- **HTTP 통신**: WebClient (비동기)
- **실시간 통신**: SSE (Server-Sent Events)
- **Database**: H2 (개발)
- **Infra**: AWS EC2
- **Security**: Spring Security

---

## 📂 프로젝트 구조
src/main/java

├── token        # 토큰 발급 및 관리

├── kis          # 한국투자증권 API 연동

├── stock        # 주식 시세 조회

├── order        # 주문/체결

├── matching     # 주문 매칭 엔진

├── member       # 회원 관리

└── security     # Spring Security 설정

---

## 🔑 환경 설정

```yaml
hantu-openapi:
  appkey: APP_KEY
  appsecret: APP_SECRET
  domain_url: https://openapivts.koreainvestment.com:29443
```

---

## 🔄 주요 기능

### 1️⃣ 토큰 발급 및 자동 관리
- OAuth2 기반 access token 발급
- API 호출 시 헤더에 자동 포함

### 2️⃣ 실시간 주식 시세 조회
- 거래량 상위 종목 랭킹 조회
- 등락률, 거래량 등 실시간 데이터 제공
- SSE 기반 실시간 스트리밍

### 3️⃣ 주문 요청 및 체결 (모의투자)
- 매수 / 매도 주문
- Kafka 기반 비동기 주문 처리
- 커스텀 주문 매칭 엔진 구현
- 주문 상태 관리: `PENDING` → `MATCHED` / `PARTIAL` / `CANCELLED` / `FAILED`

### 4️⃣ 회원 관리
- Spring Security 기반 인증/인가
- 가입 시 초기 투자금 10,000,000원 지급
- 보유 주식 및 거래 내역 조회

---

## 🔗 주문 처리 흐름
[1] 주문 요청 (매수/매도)

↓

[2] Kafka Topic 발행

↓

[3] 매칭 엔진 처리 (MatchingService)

↓

[4] 주문 상태 업데이트 (PENDING → MATCHED/PARTIAL/CANCELLED)

↓

[5] SSE로 실시간 체결 결과 전달

---

## 🚀 배포
- AWS EC2 (Ubuntu)
- JAR 패키징 후 nohup 백그라운드 실행
- prod 운영 파일 분리 운영

---

## ⚠️ 주의사항
- Access Token 만료 시 재발급 필요 (24h)
- 요청 헤더 필수 값: `Authorization`, `appkey`, `appsecret`, `tr_id`

---

## 📌 트러블슈팅

| 문제 | 원인 | 해결 |
|------|------|------|
| SSE 비동기 dispatch 권한 오류 | Spring Security가 ASYNC dispatch 재검사 | `DispatcherType.ASYNC` permitAll 처리 |
| CSS 404 오류 | Linux 파일시스템 대소문자 구분 | 파일명 소문자 통일 |
| ERR_TOO_MANY_REDIRECTS | 로컬 파일 경로 하드코딩 | application-prod.yml 분리 및 classpath 경로 수정 |

---

## 📄 라이선스
개인 학습 및 포트폴리오 용도
