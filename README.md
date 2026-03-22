# Effective-Disco BBS

Java 21 + Spring Boot 4로 구현한 게시판(BBS) 웹 애플리케이션.

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
| 컨테이너 | Docker (멀티 스테이지 빌드) |

## 주요 기능

- 게시판(자유 · 개발 · Q&A · 공지) 분류
- 게시물 CRUD · 태그 · 조회수 · 좋아요
- 댓글 · 대댓글 (1단계)
- 제목 · 내용 · 작성자 통합 키워드 검색
- 태그 필터
- 사용자 프로필 (작성 게시물 · 댓글 수 · 받은 좋아요 통계)
- REST API (JWT) + 웹 UI (세션) 이중 인증

## 빠른 시작

### 로컬 실행 (PostgreSQL 필요)

```bash
# PostgreSQL에 effectivedisco 데이터베이스 생성 후
./gradlew bootRun
```

`application.yml` 기본값: `localhost:5432`, 사용자 `postgres`, 비밀번호 `4321`

### Docker Compose로 실행

```bash
cp .env.example .env
# .env 파일에서 DB_PASSWORD, JWT_SECRET을 실제 값으로 교체

docker compose up -d --build
```

앱: `http://localhost:8080`

## 환경 변수

`.env.example`을 복사해 `.env`를 만들고 아래 값을 설정한다.

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `DB_PASSWORD` | PostgreSQL 비밀번호 | (필수) |
| `JWT_SECRET` | JWT 서명 키 (32자 이상) | (필수) |
| `JWT_EXPIRATION` | 토큰 유효 시간 (ms) | `86400000` |
| `APP_PORT` | 호스트 바인딩 포트 | `8080` |
| `JAVA_OPTS` | JVM 옵션 | `-Xmx512m` |

## API 엔드포인트

모든 API는 `/api` 접두사를 사용한다. 인증이 필요한 요청은 `Authorization: Bearer <token>` 헤더를 포함해야 한다.

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/auth/signup` | — | 회원가입 |
| POST | `/api/auth/login` | — | 로그인 → JWT 반환 |
| GET | `/api/posts` | — | 게시물 목록 (`keyword`, `tag`, `boardSlug`, `page`, `size`) |
| GET | `/api/posts/{id}` | — | 게시물 단건 조회 |
| POST | `/api/posts` | 필요 | 게시물 작성 |
| PUT | `/api/posts/{id}` | 필요 | 게시물 수정 (본인만) |
| DELETE | `/api/posts/{id}` | 필요 | 게시물 삭제 (본인만) |
| POST | `/api/posts/{id}/like` | 필요 | 좋아요 토글 |
| GET | `/api/boards` | — | 게시판 목록 |

## 테스트

```bash
# 전체 테스트 (H2 인메모리 DB 사용)
./gradlew test

# 단일 클래스
./gradlew test --tests "com.effectivedisco.service.PostServiceTest"
```

테스트는 PostgreSQL 없이 실행된다.

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`):

- **모든 브랜치**: 빌드 + 테스트 실행, 테스트 리포트 아티팩트 업로드
- **main 브랜치 push**: 테스트 통과 후 Docker 이미지를 GHCR(`ghcr.io/{owner}/effective-disco`)에 자동 빌드 및 푸시
