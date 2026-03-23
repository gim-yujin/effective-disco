# Effective-Disco BBS

[![CI](https://github.com/gim-yujin/effective-disco/actions/workflows/ci.yml/badge.svg)](https://github.com/gim-yujin/effective-disco/actions/workflows/ci.yml)

Java 21 + Spring Boot 4로 구현한 게시판(BBS) 웹 애플리케이션.
레트로 터미널 스타일 UI, REST API(JWT) + 웹 UI(세션) 이중 인증 구조.

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 4.0.4 |
| 빌드 | Gradle 8 (Kotlin DSL) |
| DB (운영) | PostgreSQL 16 |
| DB (테스트) | H2 (인메모리) |
| ORM | Spring Data JPA / Hibernate |
| 인증 | Spring Security — JWT (API) + 세션 폼 로그인 (웹) |
| 뷰 | Thymeleaf + thymeleaf-extras-springsecurity6 |
| 실시간 알림 | SSE (Server-Sent Events) |
| 컨테이너 | Docker (멀티 스테이지 빌드) |

## 주요 기능

### 게시물·게시판
- 게시판 분류 (자유 · 개발 · Q&A · 공지, 관리자 CRUD)
- 게시물 작성·수정·삭제 · 다중 이미지 첨부 (JPEG·PNG·GIF·WebP, 최대 5MB)
- 태그 · 조회수 · 좋아요 · 북마크
- 초안(임시저장) · 나중에 발행
- 게시물 고정(공지) — 관리자 전용

### 댓글
- 댓글·대댓글 (1단계) 작성·수정·삭제
- 수정 시 "(수정됨)" 표시

### 검색
- 키워드 검색 (제목·내용) — `q=spring`
- 태그 검색 — `q=#spring`
- 작성자 검색 — `q=@alice`
- 게시판 내 키워드·태그 필터
- 인기 태그 표시

### 소셜
- 사용자 팔로우/언팔로우 · 팔로우 피드
- 사용자 차단 (차단된 사용자의 게시물·댓글 숨김)
- 쪽지 (1:1 메시지)

### 알림
- 댓글·대댓글·좋아요 알림 실시간 전달 (SSE)
- 읽지 않은 알림 수 뱃지

### 프로필
- 프로필 사진 업로드
- bio · 이메일 변경 · 비밀번호 변경
- 활동 통계 (게시물 수 · 댓글 수 · 받은 좋아요 · 팔로워 · 팔로잉)

### 관리자
- 사용자 권한 부여·회수 · 계정 정지(기간/영구)
- 게시물·댓글 강제 삭제 · 신고 관리

## 빠른 시작

### Docker Compose로 실행 (권장)

```bash
cp .env.example .env
# .env 파일에서 DB_PASSWORD, JWT_SECRET을 실제 값으로 교체

docker compose up -d --build
# http://localhost:8080 접속
```

### 로컬 실행 (PostgreSQL 필요)

```bash
# PostgreSQL에 effectivedisco 데이터베이스 생성 후
./gradlew bootRun
```

`application.yml` 기본값: `localhost:5432`, 사용자 `postgres`, 비밀번호 `4321`

## 부하 테스트 (k6)

Redis/Kafka 없이 애플리케이션 내부 진단 경로와 `k6`만으로 BBS 부하를 측정한다.

```bash
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
./loadtest/run-bbs-load.sh
```

- `loadtest/k6/bbs-load.js`: 게시판 목록, 핫 게시물 상세, 검색, 게시물/댓글 작성, 멱등 좋아요/해제 경쟁 시나리오
- `loadtest/results/k6-summary-*.json`: 시나리오별 p95/p99와 실패율
- `loadtest/results/server-metrics-*.json`: duplicate-key 충돌 수, DB pool timeout 수, max awaiting connection

자세한 실행 방법은 [loadtest/README.md](/home/admin0/effective-disco/loadtest/README.md) 참고.

## 개발 명령어

```bash
# 전체 테스트 실행 (H2 인메모리 DB, PostgreSQL 불필요)
./gradlew test

# 특정 테스트 클래스 실행
./gradlew test --tests "com.effectivedisco.service.PostServiceTest"

# 특정 테스트 메서드 실행
./gradlew test --tests "com.effectivedisco.service.PostServiceTest.getPost_success_returnsPostResponse"

# JAR 빌드 (테스트 스킵)
./gradlew bootJar -x test
```

테스트 리포트: `build/reports/tests/test/index.html`

## 환경 변수

`.env.example`을 복사해 `.env`를 만들고 아래 값을 설정한다.

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `DB_PASSWORD` | PostgreSQL 비밀번호 | (필수) |
| `JWT_SECRET` | JWT 서명 키 (32자 이상 권장) | (필수) |
| `JWT_EXPIRATION` | 토큰 유효 시간 (ms) | `86400000` (24시간) |
| `APP_PORT` | 호스트 바인딩 포트 | `8080` |
| `APP_BASE_URL` | 비밀번호 재설정 링크 베이스 URL | `http://localhost:8080` |
| `MAIL_HOST` | SMTP 서버 호스트 (미설정 시 콘솔 로그 출력) | — |
| `JAVA_OPTS` | JVM 옵션 | `-Xmx512m` |

## 아키텍처 개요

### 이중 Security FilterChain

```
/api/**  →  apiFilterChain   JWT Bearer · Stateless · CSRF 비활성화
/**      →  webFilterChain   폼 로그인 · 세션 · CSRF 활성화
```

- REST API(`/api/**`)는 `Authorization: Bearer <token>` 헤더로 인증한다.
- 웹 UI(`/**`)는 Spring Security 기본 폼 로그인(`/login`)을 사용한다.

### 컨트롤러 구조

```
controller/
├── AuthController.java          # POST /api/auth/signup, /login
├── PostController.java          # REST /api/posts
├── CommentController.java       # REST /api/posts/{id}/comments
└── web/
    ├── BoardWebController.java  # 홈·게시판·게시물·댓글 UI
    ├── UserWebController.java   # 프로필·설정·팔로우·차단
    ├── SearchWebController.java # GET /search
    ├── MessageWebController.java
    ├── NotificationWebController.java
    └── AdminWebController.java
```

### 예외 → HTTP 상태 코드 매핑 (REST API)

`GlobalExceptionHandler`(`@RestControllerAdvice`)가 `/api/**`에서만 동작한다.

| 예외 | HTTP |
|------|------|
| `IllegalArgumentException` | 400 |
| `MethodArgumentNotValidException` | 400 |
| `AuthenticationException` | 401 |
| `AccessDeniedException` | 403 |

웹 컨트롤러에서는 예외 대신 리다이렉트로 처리한다.

## API 엔드포인트

모든 API는 `/api` 접두사를 사용한다. 인증이 필요한 요청은 `Authorization: Bearer <token>` 헤더를 포함해야 한다.

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/auth/signup` | — | 회원가입 |
| POST | `/api/auth/login` | — | 로그인 → JWT 반환 |
| GET | `/api/posts` | — | 게시물 목록 (`keyword`, `tag`, `boardSlug`, `sort`, `page`, `size`) |
| GET | `/api/posts/{id}` | — | 게시물 단건 조회 |
| POST | `/api/posts` | 필요 | 게시물 작성 |
| PUT | `/api/posts/{id}` | 필요 | 게시물 수정 (본인만) |
| DELETE | `/api/posts/{id}` | 필요 | 게시물 삭제 (본인만) |
| POST | `/api/posts/{id}/like` | 필요 | 좋아요 등록(멱등) |
| DELETE | `/api/posts/{id}/like` | 필요 | 좋아요 해제(멱등) |
| GET | `/api/boards` | — | 게시판 목록 |

Swagger UI: `http://localhost:8080/swagger-ui.html`

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`):

- **모든 브랜치**: 빌드 + 테스트 실행, 테스트 리포트 아티팩트 업로드
- **main 브랜치 push**: 테스트 통과 후 Docker 이미지를 GHCR(`ghcr.io/{owner}/effective-disco`)에 자동 빌드 및 푸시
