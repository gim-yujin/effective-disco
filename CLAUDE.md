# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# 테스트 실행 (H2 인메모리 DB 사용, PostgreSQL 불필요)
./gradlew test

# 단일 테스트 클래스 실행
./gradlew test --tests "com.effectivedisco.service.PostServiceTest"

# 단일 테스트 메서드 실행
./gradlew test --tests "com.effectivedisco.service.PostServiceTest.getPost_success_returnsPostResponse"

# 실행 가능한 JAR 빌드 (테스트 스킵)
./gradlew bootJar -x test

# 로컬 실행 (PostgreSQL이 localhost:5432에 실행 중이어야 함)
./gradlew bootRun

# Docker로 전체 스택 실행
cp .env.example .env   # DB_PASSWORD, JWT_SECRET 값 교체 후
docker compose up -d --build
```

## 아키텍처 개요

### 이중 Security FilterChain

`SecurityConfig`에 `@Order`가 다른 FilterChain이 두 개 존재한다.

| 체인 | 매처 | 인증 방식 | 세션 |
|------|------|-----------|------|
| `apiFilterChain` (Order 1) | `/api/**` | JWT Bearer 토큰 | Stateless |
| `webFilterChain` (Order 2) | `/**` | 폼 로그인 (세션) | 세션 기반 |

`/api/**` 경로는 `JwtAuthenticationFilter`가 처리하고, 웹 경로는 Spring Security 기본 폼 로그인을 사용한다. `@WebMvcTest`를 쓸 때는 두 체인을 모두 고려해야 하므로 컨트롤러 테스트는 `@SpringBootTest` + MockMvc(webAppContextSetup)로 작성한다.

### 컨트롤러 분리

- `controller/` — REST API (`/api/**`): JSON 요청/응답, JWT 인증
- `controller/web/` — 웹 UI (`/`, `/boards/**`, `/posts/**`, `/users/**`): Thymeleaf 렌더링, 세션 인증

### 서비스 분리: 쓰기 vs 읽기

- `PostService` (쓰기 전용): create, update, delete, toggle favorite, pin
- `PostReadService` (읽기 전용): list, search, pagination, scroll-cursor, filter, sort
  - `@Transactional(readOnly = true)`로 Hibernate flush 생략 및 replica 라우팅 가능

### 게시물 데이터 모델

- `Post.board`는 nullable (`@JoinColumn(name = "board_id")`에 `nullable = false` 없음) — 게시판 기능 도입 이전 데이터와의 하위 호환
- `Post.tags`는 `@ManyToMany` (중간 테이블 `post_tags`)
- 조회수 중복 방지는 DB가 아닌 `HttpSession`의 `Set<Long> viewedPosts`로 처리 (웹 컨트롤러)

### 알림 시스템

이벤트 기반 아키텍처로 비즈니스 트랜잭션과 알림 생성을 분리한다.

1. 서비스가 `NotificationRequestedEvent`를 발행
2. `NotificationEventListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 수신 — 원 트랜잭션이 커밋된 경우에만 알림 생성
3. `storeNotificationAfterCommit()`이 `@Transactional(propagation = REQUIRES_NEW)`로 별도 트랜잭션에서 실행

**Unread counter**: `User.unreadNotificationCount`는 비정규화 카운터다. `incrementUnreadNotificationCount` / `decrementUnreadNotificationCount(delta)` atomic UPDATE로 관리하며, notification hot path에 FOR UPDATE lock 없이 동작한다. `markPageAsReadByIds`와 `markAllAsReadUpToId`의 `WHERE isRead = false` 조건이 실제 전환 수를 정확히 반환하므로 decrement delta로 사용해도 counter drift가 발생하지 않는다.

**SSE 실시간 푸시**: `SseEmitterService`가 `ConcurrentHashMap<String, SseEmitter>`로 사용자별 연결을 관리한다. 단일 서버 인메모리 방식이므로 클러스터 배포 시 Redis Pub/Sub 등이 필요하다.

**알림 설정**: `NotificationSetting` 엔티티로 사용자별/타입별 수신 여부를 관리한다. row 부재 = 수신 허용 (lazy creation).

### 동시성 패턴

- `findByUsernameForUpdate()` (`PESSIMISTIC_WRITE`): bookmark/follow/block toggle 등 "조회 후 삽입" race를 방지하기 위해 요청 주체 User 행을 잠금
- **Projection 기반 최적화**: `NotificationRecipientSnapshot`, `CommentAuthorSnapshot`, `SecurityUserSnapshot` 등 인터페이스 projection으로 hot path의 엔티티 hydration 비용을 줄임

### 예외 → HTTP 상태 코드 매핑

`GlobalExceptionHandler`(`@RestControllerAdvice`)가 REST API에서만 동작한다.

| 예외 | HTTP |
|------|------|
| `IllegalArgumentException` | 400 |
| `MethodArgumentNotValidException` | 400 |
| `AuthenticationException` | 401 |
| `AccessDeniedException` | 403 |

웹 컨트롤러에서는 예외 대신 리다이렉트로 처리한다.

### 테스트 전략

- **단위 테스트** (`security/`, `service/`): `@ExtendWith(MockitoExtension.class)`, Spring Context 없음
- **통합 테스트** (`controller/`): `@SpringBootTest` + `@ActiveProfiles("test")` + `@Transactional`
  - H2 인메모리 DB 사용 (`src/test/resources/application.yml`)
  - `JwtTokenProvider`를 직접 주입해 Bearer 토큰 생성 후 `Authorization` 헤더에 첨부
- **동시성 통합 테스트** (`NotificationAfterCommitIntegrationTest`): `ExecutorService` + `CountDownLatch`로 concurrent store/read-all/read-page 후 unread counter == unread rows 검증

### 도메인 특이사항

- **Message**: `deletedBySender` / `deletedByRecipient` 플래그로 소프트 삭제 (양쪽 독립)
- **Block**: 단방향. 차단자의 게시물/댓글 조회에 영향
- **Bookmark**: `BookmarkFolder`가 nullable (null = 미분류)
- **계정 정지**: `User.suspended` + `suspendedUntil` (null = 영구 정지). `isCurrentlySuspended()`가 경과 시간 자동 판단

### Jackson

Spring Boot 4.x는 Jackson 3.x를 사용하므로 import가 `tools.jackson.databind.ObjectMapper`다 (`com.fasterxml.jackson` 아님).

### 기본 게시판 시딩

`BoardDataInitializer`(CommandLineRunner)가 애플리케이션 시작 시 게시판이 0개일 때만 free/dev/qna/notice 게시판을 생성한다. `@SpringBootTest` 통합 테스트에서는 `@Transactional`로 롤백되므로 간섭하지 않는다.

### Load Test 인프라

`@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")`로 조건부 활성화된다.

- `LoadTestActionController`: 공개 REST API가 아닌 서비스 메서드 (follow, bookmark, block, notifications)를 `/internal/load-test/actions/*` 경로로 노출해 k6가 직접 호출
- `LoadTestStepProfiler`: hot path 내부 단계별 wall-time / SQL count / SQL exec time 기록
- baseline (`/notifications/read-page`) vs stress (`/notifications/read-all`) 시나리오 분리

### 환경 변수 오버라이드

Spring Boot relaxed binding으로 환경 변수 → 프로퍼티 자동 매핑된다.

| 환경 변수 | 프로퍼티 |
|-----------|----------|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` |
| `JWT_SECRET` | `jwt.secret` |
| `JWT_EXPIRATION` | `jwt.expiration` |
| `SPRING_JPA_SHOW_SQL` | `spring.jpa.show-sql` |
