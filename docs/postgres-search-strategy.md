# PostgreSQL Search Strategy

이 문서는 Effective-Disco 검색 경로에 PostgreSQL `FTS` 와 `pg_trgm` 를 도입한 근거와 구현 방식을 기록한다.
목표는 "새 기술을 썼다"가 아니라, 현재 병목과 검색 의미를 함께 고려했을 때 왜 이 조합이 맞는지 남기는 것이다.

## 배경

2026-03-24 기준 load test 와 `EXPLAIN ANALYZE` 결과는 다음을 보여줬다.

- 전체 검색 병목은 `LOWER(... LIKE '%keyword%')` 기반 `title/content/username` 검색이었다.
- `board + keyword list` 와 `board + keyword count(*)` 가 모두 큰 `posts` slice 를 다시 훑었다.
- `post.list` 와 검색 `count(*)` 가 `slowActiveQueries` 상위에 계속 남았다.
- `title/content` 와 `username` 은 검색 특성이 다르다.
  - `title/content` 는 문서 검색에 가깝다.
  - `username` 은 substring 기반 탐색 기대가 크다.

즉, 한 가지 기법으로 둘을 모두 해결하려고 하면 성능이나 검색 의미 둘 중 하나가 깨질 가능성이 컸다.

## 문제 정의

기존 검색은 아래 두 문제를 동시에 갖고 있었다.

1. 성능 문제

- `%keyword%` 는 일반 btree 인덱스를 거의 타지 못한다.
- 목록 본문과 `count(*)` 가 모두 비슷한 filter cost 를 다시 부담한다.
- 결과적으로 read pressure 가 DB pool 대기와 timeout 으로 이어졌다.

2. 의미 문제

- 사용자 입장에서는 `username` 검색이 substring 으로 동작하길 기대한다.
- 반면 `title/content` 는 단순 substring 보다 단어 기반 문서 검색이 더 자연스럽다.
- 따라서 `FTS only` 나 `trigram only` 로 통일하면 한쪽 의미가 어색해질 수 있다.

## 고려한 선택지

### 1. 기존 `LIKE` 유지

장점:

- 검색 의미 변화가 없다.
- 구현이 가장 단순하다.

단점:

- 현재 병목을 그대로 유지한다.
- `count(*)` 비용도 함께 남는다.

판단:

- 채택 불가.

### 2. `FTS only`

장점:

- `title/content` 검색에는 잘 맞는다.
- GIN index 로 대량 텍스트 filter 비용을 크게 줄일 수 있다.

단점:

- `username` substring 기대와 맞지 않는다.
- 한국어/짧은 토큰/부분 문자열 검색에서는 기대와 다르게 느껴질 수 있다.

판단:

- 일부 경로에는 맞지만 전체 검색을 단독으로 맡기기엔 부적절.

### 3. `pg_trgm only`

장점:

- substring semantics 를 그대로 유지할 수 있다.
- `LIKE '%keyword%'` 도 인덱스 지원이 가능하다.

단점:

- 긴 문서 본문(`content`)까지 trigram 으로만 처리하면 인덱스와 검색 비용이 커질 수 있다.
- 문서 검색 품질 관점에서는 FTS 보다 덜 자연스럽다.

판단:

- `username` 에는 적합하지만 `title/content` 주 검색 전략으로는 과하다.

### 4. `FTS + pg_trgm` 하이브리드

장점:

- `title/content` 는 FTS 로, `username` 은 trigram-backed LIKE 로 분리할 수 있다.
- 검색 의미를 크게 바꾸지 않으면서 병목 query 를 직접 줄인다.
- PostgreSQL 에서는 최적화하고, 테스트/H2 환경에서는 fallback 을 둘 수 있다.

단점:

- 구현 복잡도가 높아진다.
- PostgreSQL 전용 native query 와 초기화 로직이 필요하다.

판단:

- 채택.

## 채택한 설계

### 검색 의미

- `title/content`: PostgreSQL `FTS`
- `username`: 기존 substring semantics 유지

즉, 검색 의미는 다음과 같다.

- 게시물 본문 검색은 "단어 기반 문서 검색" 으로 본다.
- 작성자 검색은 "부분 문자열 검색" 으로 본다.

### 왜 `simple` text search config 인가

- 현재 서비스는 한국어 BBS 이다.
- PostgreSQL 기본 `english` config 는 영어 stemming/stopword 에 맞춰져 있어 한국어 본문에는 부적절하다.
- `simple` config 는 공격적인 stemming 을 하지 않아 현재 데이터에 더 안전하다.

주의:

- `simple` 도 한국어 형태소 분석기는 아니다.
- 따라서 한국어 품질을 극단적으로 끌어올리는 목적이라면 별도 한국어 검색 스택이 필요할 수 있다.
- 이번 도입 목표는 우선 "현재 LIKE scan 병목 제거" 이다.

### 왜 relevance 정렬이 아니라 최신순을 유지했는가

- 현재 제품의 검색 UX 는 최신순 기반이다.
- 이번 변경의 1차 목표는 성능 개선이지 검색 ranking 정책 변경이 아니다.
- 따라서 FTS 는 filter 최적화 용도로 먼저 도입하고, 정렬은 `created_at DESC` 를 유지했다.

## 구현 요약

### 저장소 계층

- [PostRepositoryCustom](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryCustom.java)
  - 키워드 검색 전용 커스텀 저장소 인터페이스
- [PostRepositoryImpl](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  - PostgreSQL: FTS + trigram-backed username LIKE native SQL
  - H2: 기존 의미를 유지하는 substring fallback native SQL

핵심 이유:

- PostgreSQL 에서는 `to_tsvector/plainto_tsquery` 와 `gin_trgm_ops` 를 직접 써야 한다.
- 하지만 CI 의 H2 테스트 프로필까지 그 SQL 을 강제하면 회귀 테스트가 깨진다.
- 그래서 DB 종류에 따라 query strategy 를 나눴다.

### PostgreSQL 초기화

- [PostgresSearchInfrastructureInitializer](/home/admin0/effective-disco/src/main/java/com/effectivedisco/config/PostgresSearchInfrastructureInitializer.java)
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm`
  - `idx_posts_search_fts`
  - `idx_users_username_trgm`

설계 기준:

- extension/index 생성 실패는 correctness 문제가 아니라 성능 문제다.
- 권한 없는 환경에서 startup 전체를 막지 않도록 warning 후 계속 진행한다.

### 인덱스 전략

- `posts(board_id, draft, created_at DESC)`
  - 최신순 목록/게시판 검색 정렬 지원
- `post_tags(tag_id, post_id)`
  - 태그 기반 목록/카운트 fan-out 완화
- `idx_posts_search_fts`
  - `title/content` FTS filter 지원
- `idx_users_username_trgm`
  - `username` substring LIKE 지원

## 기대 효과

1. 검색 본문

- `title/content` 의 `%keyword%` full scan 성격을 줄인다.
- `count(*)` 도 같은 FTS filter 를 사용하게 해 본문/카운트 비용을 같이 줄인다.

2. 작성자 검색

- `username` 검색 semantics 는 그대로 유지한다.
- 대신 PostgreSQL 에서 `pg_trgm` 인덱스로 `LIKE '%keyword%'` 비용을 낮춘다.

3. 회귀 방지

- H2 테스트 프로필에서도 검색 결과 의미는 유지된다.
- [PostListOptimizationIntegrationTest](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java)
  에 username substring 회귀 테스트를 추가했다.

## 비목표

- 이번 변경은 한국어 검색 품질 자체를 완성하는 작업이 아니다.
- relevance ranking 개편 작업도 아니다.
- `@username` prefix 검색 같은 별도 UX 는 기존 경로를 유지한다.

## 남은 과제

- 새 인덱스와 FTS 도입 후 `EXPLAIN ANALYZE` 를 다시 수집한다.
- load test 기준으로 검색 `p95/p99`, `dbPoolTimeouts`, `slowActiveQueries` 변화를 다시 비교한다.
- 필요하면 다음 단계에서 아래를 검토한다.
  - `content` 를 목록 응답에서 제외
  - trigram 을 `title` 까지 확장할지 여부
  - 한국어 검색 품질 향상을 위한 별도 검색 전략

## 후속 구현과 실측 결과

2026-03-24 후속 측정에서 드러난 사실은 하나였다.
`FTS + pg_trgm` 를 넣는 것만으로는 충분하지 않았고, PostgreSQL 이 실제로 그 인덱스를 쓰게 만드는 `query shape` 가 더 중요했다.

### 왜 추가 rewrite 가 필요했는가

- 초기 구현은 `FTS OR username LIKE` 를 한 predicate 로 묶고 있었다.
- 이 상태에서는 PostgreSQL 이 `idx_posts_search_fts` 를 핵심 플랜으로 채택하지 못했고, `board/draft` 범위를 먼저 읽은 뒤 FTS/LIKE 를 필터링하는 형태가 남았다.
- 즉 기술 도입 근거는 맞았지만, 구현 shape 가 그 근거를 충분히 살리지 못했다.

### 추가로 적용한 구조

- `title/content` 검색은 `FTS branch`
- `username` 검색은 `pg_trgm` 기반 `LIKE branch`
- 두 branch 는 `post id` 만 뽑아 `UNION` 으로 합치고, dedup 된 id 집합으로 본문/카운트를 계산

이 구조를 택한 이유:

- FTS 와 trigram 인덱스를 서로 독립적으로 최적화하게 만들기 위해서다.
- `OR` predicate 를 없애야 PostgreSQL 이 각 branch 의 인덱스를 별도 플랜으로 잡을 수 있었다.

### 실측 결과

`EXPLAIN ANALYZE`:

- `board + keyword list`: `65.411ms -> 14.852ms`
- `board + keyword count(*)`: `155.216ms -> 6.334ms`
- `tag count(*)`: `12.660ms -> 13.781ms`

관측된 실행계획:

- `idx_posts_search_fts` 사용
- `idx_users_username_trgm` 사용
- `idx_post_tags_tag_post` 사용

검색 집중 soak:

- 이전: `p95=1827.55ms`, `p99=1984.91ms`, `unexpected_response_rate=0.1274`, `dbPoolTimeouts=503`
- 이후: `p95=437.57ms`, `p99=639.39ms`, `unexpected_response_rate=0.0001`, `dbPoolTimeouts=1`

반복 ramp-up:

- 이전: `0.6` 도 `5/5 FAIL`, `highest stable factor = n/a`
- 이후: `0.6/0.7/0.8` 모두 `5/5 PASS`, `highest stable factor = 0.8`

### 결론

- 이 프로젝트에서 PostgreSQL 검색 최적화의 핵심 근거는 "`FTS` 와 `pg_trgm` 을 쓴다" 자체가 아니라, "검색 의미를 유지하면서 각 인덱스가 실제로 선택되는 query shape 로 분리한다" 에 있다.
- 즉 기술 선택과 query shape 는 분리된 문제가 아니다.
- 이번 실측은 `FTS + pg_trgm + branch/UNION rewrite` 조합이 현재 검색 병목에 대해 실효성이 있다는 근거다.
