# 로컬 다중 서버 실행용 이미지 - 먼저 ./gradlew bootJar 로 JAR를 만든 뒤 docker compose --profile cluster up -d --build
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/stockTrade-0.0.1-SNAPSHOT.jar app.jar
# application-secret.yaml(KIS/카카오/네이버 키)은 이미지에 넣지 않고 compose에서 /app 으로 마운트
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
