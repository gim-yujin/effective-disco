# Load Test Optimization

이 문서는 부하 테스트 중 식별한 병목과 적용한 최적화, 전후 실측 결과를 누적 기록하는 문서다.
정합성 문서가 "상태가 깨지지 않는가"를 기록한다면, 이 문서는 "어디가 느렸고 무엇을 줄였는가"를 기록한다.

## 2026-03-24 `post.list` 최적화

상태: 완료

### 배경

- `loadtest` 프로필 short soak에서 `post.list` 가 평균 wall time 기준 가장 비싼 읽기 경로였다.
- 당시 병목 프로파일은 다음과 같았다.
  - 실행 시각: `20260324-032032`
  - artifact: `loadtest/results/soak-20260324-032032-server.json`
  - `averageWallTimeMs = 91.81`
  - `averageSqlExecutionTimeMs = 16.54`
  - `averageSqlStatementCount = 54.84`
  - `maxSqlStatementCount = 63`

### 원인

- 목록 본문 쿼리는 `Post` 페이지만 읽고, `PostResponse` 변환 시점에 `author`, `board`, `tags`, `images` 를 추가로 접근했다.
- `author/board` 는 `ManyToOne LAZY`, `tags/images` 는 컬렉션 LAZY 라서 페이지 내 게시물 수만큼 추가 select 가 따라붙었다.
- 결과적으로 `post.list` 한 번에 SQL 문 수가 50개 이상으로 불어나 DB pool 대기열을 키웠다.

### 적용한 변경

- 목록/검색/정렬/프로필용 `PostRepository` 페이징 쿼리에 `@EntityGraph(attributePaths = {"author", "board"})` 를 추가했다.
- 현재 페이지의 게시물 ID만 모아 `tags`, `images` 를 각각 한 번씩 preload 하는 보조 쿼리를 추가했다.
- `PostService` 에 `toPostResponsePage()`, `toPostResponseList()`, `preloadListRelations()` 를 도입해 목록 DTO 변환 전에 page-scope preload 를 수행하도록 바꿨다.
- `getPosts()`, `getPostsByAuthor()`, `getDrafts()`, `getPinnedPosts()` 에 `@Transactional(readOnly = true)` 를 부여해 같은 영속성 컨텍스트 안에서 preload 와 DTO 변환이 일관되게 동작하게 했다.
- Hibernate statistics 기반 통합 테스트를 추가해 `getPosts()` 1회 SQL statement 수가 `6` 이하인지 검증했다.

### 전후 비교

최적화 전:

- 실행 시각: `20260324-032032`
- `averageWallTimeMs = 91.81`
- `averageSqlExecutionTimeMs = 16.54`
- `averageSqlStatementCount = 54.84`
- `maxWallTimeMs = 384.08`
- `maxSqlStatementCount = 63`

최적화 후:

- 실행 시각: `20260324-033507`
- artifact: `loadtest/results/soak-20260324-033507-server.json`
- `averageWallTimeMs = 19.35`
- `averageSqlExecutionTimeMs = 16.19`
- `averageSqlStatementCount = 4.80`
- `maxWallTimeMs = 72.71`
- `maxSqlStatementCount = 5`

### 해석

- SQL 실행 시간 자체는 `16.54ms -> 16.19ms` 로 크게 변하지 않았다.
- 반면 SQL 문 수는 `54.84 -> 4.80`, wall time 은 `91.81ms -> 19.35ms` 로 크게 줄었다.
- 즉, 이번 병목은 "느린 단일 쿼리" 보다 "과도한 쿼리 fan-out과 ORM 연관 로딩" 에 더 가까웠다.
- 이 최적화는 DB가 실제 일을 덜 하게 만들었다기보다, 목록 1회당 왕복 횟수를 줄여 pool 압박과 애플리케이션 측 대기 시간을 낮춘 것이다.

### 검증

회귀 + 통합 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.service.PostListOptimizationIntegrationTest" \
  --tests "com.effectivedisco.service.PostServiceTest" \
  --tests "com.effectivedisco.controller.PostControllerTest" \
  --tests "com.effectivedisco.controller.web.BoardWebControllerTest" \
  --tests "com.effectivedisco.controller.web.SearchWebControllerTest"
```

결과:

- 통합 테스트 통과
- `PostListOptimizationIntegrationTest` 에서 `getPrepareStatementCount() <= 6` 확인
- short soak 재실행에서 `duplicateKeyConflicts=0`, `dbPoolTimeouts=0`, SQL mismatch `0`

### 남은 과제

- 재측정 결과를 바탕으로 `comment.create`, `notification.store` 를 다음 병목 후보로 분석
- 필요 시 `post.list` 의 검색/태그 필터 쿼리 플랜과 인덱스도 별도로 점검

### 후속 경계점 재측정

실행 날짜:

- 2026-03-24

실행 조건:

- 결과 디렉터리: `loadtest/results/boundary-repeat-opt-20260324-034537`
- 반복 횟수: `5회`
- `STOP_ON_K6_THRESHOLD=0`
- `STOP_ON_HTTP_P99_MS=800`
- `STAGE_FACTORS=1,1.25,1.5,1.75,2,2.25,2.5,3,3.5,4`
- `BROWSE_DURATION=12s`, `HOT_POST_DURATION=12s`, `SEARCH_DURATION=12s`
- `WRITE_STAGE_ONE_DURATION=8s`, `WRITE_STAGE_TWO_DURATION=8s`

재측정 결과:

- `1.25x`: `5/5 PASS`
- `1.25x` 전체 `p99`: `641.39ms~686.97ms`, 평균 `664.17ms`
- `1.5x`: `2/5 LIMIT`, `3/5 FAIL`
- `1.5x` 전체 `p99`: `868.06ms~963.63ms`, 평균 `904.64ms`
- `1.5x` 실패 런에서는 `unexpected_response_rate=0.0005~0.0020`, `dbPoolTimeouts=7~30`
- `1.5x` 전체 런에서 `maxActiveConnections=20`, `maxThreadsAwaitingConnection=191~194`
- 전체 반복 런에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0`

해석:

- `post.list` 자체는 빨라졌지만, 전체 시스템의 안정 구간은 `1.25x` 에 그대로 머물렀다.
- 즉 이번 최적화는 목록 경로 병목을 제거했지만, 전체 한계점은 `comment.create`, `notification.store`, 또는 그 외 쓰기 경로에서 다시 결정되고 있다.
- 다음 최적화 우선순위는 `post.list` 가 아니라 `comment.create` 와 `notification.store` 다.

## 2026-03-24 `comment.create` / `notification.store` 최적화

상태: 완료

### 배경

- `post.list` 최적화 이후 short soak 기준 다음 병목 후보는 `comment.create` 와 `notification.store` 였다.
- 최적화 직전 기준 artifact 는 `loadtest/results/soak-20260324-033507-server.json` 이다.
- 당시 병목 프로파일은 다음과 같았다.
  - `comment.create averageWallTimeMs = 2.01`
  - `comment.create averageSqlExecutionTimeMs = 1.02`
  - `comment.create averageSqlStatementCount = 4.95`
  - `notification.store averageWallTimeMs = 4.23`
  - `notification.store averageSqlExecutionTimeMs = 3.61`
  - `notification.store averageSqlStatementCount = 3.00`

### 원인

- `comment.create` 는 댓글 1건 저장을 위해 `Post` 와 `User` 엔티티를 각각 전체 조회했고, 댓글 알림 생성 시 `post.author` LAZY 접근으로 추가 select 를 더 탔다.
- 새 댓글 응답도 `CommentResponse` 생성 과정에서 작성자와 `replies` 컬렉션을 다시 접근해, freshly-created 댓글 경로에 불필요한 연관 로딩이 따라붙을 여지가 있었다.
- `notification.store` 는 수신자 `User` 를 잠근 뒤 알림 row 를 저장하고, unread counter 증가 후 after-commit push 시 unread count 를 다시 읽었다.
- 즉 `notification.store` 는 "잠금 조회 + INSERT + unread count 재조회" 구조라 저장 경로 자체에 여분의 read 가 붙어 있었다.

### 적용한 변경

- `PostRepository` 에 `CommentNotificationTarget` projection 을 추가해 `comment.create` 에 필요한 `post.id`, `post.author.username` 만 읽도록 바꿨다.
- `UserRepository` 에 `CommentAuthorSnapshot` projection 을 추가해 댓글 작성자의 `id`, `username`, `profileImageUrl` 만 읽도록 바꿨다.
- `CommentService.createComment()` 는 projection 으로 존재 여부를 확인한 뒤 `EntityManager.getReference()` 로 연관을 묶어 댓글을 저장하도록 변경했다.
- `NotificationService` 에 username 기반 `notifyComment()` overload 를 추가해 댓글 알림 경로에서 `post.author` 추가 로딩을 없앴다.
- `CommentResponse` 에 freshly-created 댓글 전용 생성자를 추가해 새 댓글 응답이 `author/replies` LAZY 로딩 없이 직렬화되도록 바꿨다.
- `NotificationService.storeNotificationAfterCommit()` 는 잠금을 잡은 `User` 엔티티의 `unreadNotificationCount` 를 직접 증가시키고, 확정된 값을 after-commit SSE push 에 그대로 넘기도록 바꿨다.
- `getAndMarkAllRead()` 는 unread 가 이미 `0` 이면 bulk update 를 건너뛰도록 정리했다.
- Hibernate statistics 기반 `CommentCreateOptimizationIntegrationTest` 를 추가해 `comment.create` hot path 의 statement 수가 `4` 이하인지 고정했다.

### 전후 비교

최적화 전:

- 실행 시각: `20260324-033507`
- artifact: `loadtest/results/soak-20260324-033507-server.json`
- `comment.create averageWallTimeMs = 2.01`
- `comment.create averageSqlExecutionTimeMs = 1.02`
- `comment.create averageSqlStatementCount = 4.95`
- `notification.store averageWallTimeMs = 4.23`
- `notification.store averageSqlExecutionTimeMs = 3.61`
- `notification.store averageSqlStatementCount = 3.00`

최적화 후:

- 실행 시각: `20260324-040618`
- artifact: `loadtest/results/soak-20260324-040618-server.json`
- `comment.create averageWallTimeMs = 2.09`
- `comment.create averageSqlExecutionTimeMs = 1.01`
- `comment.create averageSqlStatementCount = 4.00`
- `notification.store averageWallTimeMs = 1.95`
- `notification.store averageSqlExecutionTimeMs = 1.55`
- `notification.store averageSqlStatementCount = 2.00`

### 해석

- `comment.create` 는 wall time 자체는 거의 비슷했지만, statement 수를 `4.95 -> 4.00` 으로 줄여 SQL fan-out 을 제거했다.
- `comment.create` 는 이제 "projection 2회 + comment insert + commentCount update" 수준으로 고정되어, 게시물 작성자/작성자 프로필/대댓글 컬렉션 접근 때문에 SQL 이 더 늘어나지 않는다.
- `notification.store` 는 statement 수를 `3.00 -> 2.00` 으로 줄였고, wall time 도 `4.23ms -> 1.95ms` 로 유의미하게 감소했다.
- 즉 이번 최적화는 단일 느린 쿼리를 고친 것이 아니라, 쓰기 hot path 에 붙어 있던 불필요한 read-back 과 연관 로딩을 제거한 것이다.

### 검증

회귀 + 통합 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.service.CommentServiceTest" \
  --tests "com.effectivedisco.service.CommentCreateOptimizationIntegrationTest" \
  --tests "com.effectivedisco.service.NotificationServiceTest" \
  --tests "com.effectivedisco.service.NotificationAfterCommitIntegrationTest" \
  --tests "com.effectivedisco.service.MessageServiceTest"
```

short soak:

```bash
SOAK_FACTOR=1 SOAK_DURATION=10s WARMUP_DURATION=5s SAMPLE_INTERVAL_SECONDS=2 \
  BASE_URL=http://localhost:18080 ./loadtest/run-bbs-soak.sh
```

결과:

- targeted Gradle 테스트 통과
- `CommentCreateOptimizationIntegrationTest` 에서 `comment.create` 의 `getPrepareStatementCount() <= 4` 확인
- short soak 재실행 결과 `unexpected_response_rate=0.0000`, `duplicateKeyConflicts=0`, `dbPoolTimeouts=0`, SQL mismatch `0`
- soak 리포트는 기존 글로벌 p99 threshold 때문에 `FAIL` 로 종료됐지만, 이번 최적화 대상 경로의 정합성 깨짐이나 pool timeout 증거는 나오지 않았다.

### 남은 과제

- 같은 조건으로 반복 ramp-up 을 다시 돌려 `comment.create` / `notification.store` 최적화가 전체 안정 구간을 실제로 밀어 올렸는지 측정
- 여전히 `1.5x` 경계가 유지되면 다음 병목을 `comment.create`, `notification.store` 외의 쓰기 경로 또는 pool 설정/DB 플랜에서 찾아야 한다
