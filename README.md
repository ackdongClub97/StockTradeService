
# 📈 한국투자증권 Open API 기반 모의투자 체결 시스템(진행중)


## 📌 프로젝트 소개

한국투자증권 Open API를 활용하여
**모의투자 환경에서 주식 조회 및 주문 체결을 수행하는 시스템**입니다.

Spring Boot 기반으로 REST API 서버를 구성하고,
토큰 발급 → 시세 조회 → 주문 요청까지의 흐름을 구현합니다.

---

## 🛠 기술 스택

* Java 21+
* Spring Boot
* WebClient (비동기 HTTP 통신)
* H2 Database (개발용)
* JPA (Hibernate)

---

## 📂 프로젝트 구조

```
src/main/java
 ├── token        # 토큰 발급
 ├── stock        # 주식 조회
 ├── order        # 주문/체결
 ├── config       # 설정
 └── controller   # API 엔드포인트
```

---

## 🔑 환경 설정

### 1. application.yml

```yaml
hantu-openapi:
  appkey: APP_KEY
  appsecret: APP_SECRET
  domain_url: https://openapivts.koreainvestment.com:29443 #모의 투자 url
```
---

## 🔄 주요 기능

### 1️⃣ 토큰 발급

* OAuth2 기반 access token 발급
* API 호출 시 헤더에 포함

```
POST /oauth2/tokenP
```

---

### 2️⃣ 주식 시세 조회

* 일별 주가 조회
* 거래량, 등락률 등 데이터 제공

```
GET /uapi/domestic-stock/v1/quotations/day-stock
```

---

### 3️⃣ 주문 요청 (모의투자)

* 매수 / 매도 주문
* 체결 결과 확인

```
POST /uapi/domestic-stock/v1/trading/order-cash
```

---

## 🔗 API 호출 흐름

```
[1] 토큰 발급
   ↓
[2] 헤더 설정 (Authorization)
   ↓
[3] 시세 조회
   ↓
[4] 주문 요청
   ↓
[5] 체결 결과 확인
```

---

## 📦 예시 코드

### WebClient 설정

```java
WebClient webClient = WebClient.builder()
    .baseUrl("https://openapivts.koreainvestment.com:29443")
    .build();
```

---

## ⚠️ 주의사항

* Access Token 만료 시간 존재 (재발급 필요)
* 요청 헤더 필수 값:

    * Authorization
    * appkey
    * appsecret
    * apiurl
    * tr_id

---

## 🧪 테스트

* Postman 사용
* API 호출 시 토큰 포함 필수

---

## 📌 향후 개선 사항

* 토큰 자동 갱신
* 주문 체결 로그 저장
* 실시간 WebSocket 연동
* Redis 캐싱
* Kafka DB저장

---
 
## 📄 라이선스

개인 학습 및 테스트 용도로 사용
