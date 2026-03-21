# ────────────────────────────────────────────────────────────
# Stage 1: Build
# Gradle로 소스를 컴파일하고 실행 가능한 fat JAR를 생성한다.
# JDK가 필요한 단계이며, 최종 이미지에는 포함되지 않는다.
# ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Gradle 래퍼와 빌드 스크립트만 먼저 복사한다.
# 소스가 바뀌지 않아도 의존성 다운로드 결과를 Docker 레이어로 캐시하기 위함이다.
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# 의존성 다운로드 (소스 변경 없이 빌드 스크립트만 바뀌지 않으면 캐시 활용)
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사 후 bootJar 생성 (테스트는 CI에서 이미 검증했으므로 스킵)
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# plain JAR(라이브러리용)를 제외하고 실행 가능한 JAR만 /app.jar로 이동
RUN find build/libs -name "*.jar" ! -name "*plain*" -exec cp {} /app.jar \;

# ────────────────────────────────────────────────────────────
# Stage 2: Runtime
# JRE만 포함한 가벼운 이미지 — JDK 및 빌드 산출물은 포함되지 않는다.
# ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Builder 단계에서 생성된 JAR만 복사
COPY --from=builder /app.jar app.jar

# Spring Boot 기본 포트
EXPOSE 8080

# 컨테이너 시작 시 애플리케이션 실행
# JVM 옵션은 JAVA_OPTS 환경변수로 외부에서 주입 가능 (예: -Xmx512m)
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
