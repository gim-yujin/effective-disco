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

### 후속 경계점 재측정

실행 날짜:

- 2026-03-24

실행 조건:

- 결과 디렉터리: `loadtest/results/boundary-repeat-write-opt-20260324-042009`
- 반복 횟수: `5회`
- `STOP_ON_K6_THRESHOLD=0`
- `STOP_ON_HTTP_P99_MS=800`
- `STAGE_FACTORS=1,1.25,1.5,1.75,2,2.25,2.5,3,3.5,4`
- `BROWSE_DURATION=12s`, `HOT_POST_DURATION=12s`, `SEARCH_DURATION=12s`
- `WRITE_STAGE_ONE_DURATION=8s`, `WRITE_STAGE_TWO_DURATION=8s`

재측정 결과:

- `1.0x`: `5/5 PASS`
- `1.25x`: `3/5 PASS`, `2/5 LIMIT`
- `1.25x` LIMIT 는 모두 `http-p99-threshold`
- `1.25x` 전체 `p99`: `774.08ms~961.15ms`
- `1.5x`: 도달한 `3/3` 모두 `FAIL`
- `1.5x` 전체 `p99`: `1046.84ms~1581.89ms`
- `1.5x` 실패 런에서는 `unexpected_response_rate=0.0003~0.0056`, `dbPoolTimeouts=4~79`
- 전체 반복 런에서 `maxActiveConnections=20`, `maxThreadsAwaitingConnection=191~200`
- 전체 반복 런에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0`

해석:

- `comment.create` 와 `notification.store` 자체의 SQL fan-out 은 줄었지만, mixed ramp-up 기준 전체 한계점은 올라가지 않았다.
- 오히려 이번 반복 샘플 기준으로는 `1.25x` 도 `5/5 PASS` 를 유지하지 못해, 보수적으로는 `1.0x 안정 / 1.25x 경계 / 1.5x 실패` 로 보는 편이 맞다.
- 즉 이번 최적화는 hot path 하나하나를 가볍게 만들었지만, 시스템 전체 병목은 여전히 DB pool 포화와 다른 혼합 경로 경합에 묶여 있다.
- 다음 단계는 `pool 포화를 유발하는 남은 경로` 를 다시 좁히는 것이다. 특히 `write_posts_and_comments` 와 mixed race 전체에서 어떤 경로가 커넥션을 오래 점유하는지 재계측이 필요하다.

## 2026-03-24 `JWT 인증 조회` 계측 분리 + 로컬 캐시 최적화

상태: 완료

### 배경

- `post.list`, `comment.create`, `notification.store` 를 줄인 뒤에도 반복 ramp-up 에서는 `1.25x 경계 / 1.5x 실패` 가 유지됐다.
- 짧은 soak 실행 중 실제 timeout stack trace를 보면 서비스 메서드보다 먼저 `CustomUserDetailsService.loadUserByUsername()` 에서 커넥션 획득 timeout 이 터지는 경우가 반복됐다.
- 즉 남은 pool 포화 원인을 더 잘게 쪼개려면 "비즈니스 로직 경로"와 별개로 "JWT 인증 조회 경로"를 따로 계측해야 했다.

### 원인

- `/api/**` 요청은 JWT 필터를 거치며, 같은 사용자의 토큰이 연속으로 들어와도 매 요청마다 `UserDetailsService` 를 통해 DB에서 사용자 인증 정보를 다시 읽었다.
- 이 경로는 요청 수가 많은 읽기/좋아요/댓글 API 전체에 공통으로 붙기 때문에, 서비스 메서드가 짧아져도 인증 조회가 pool을 먼저 잠식할 수 있었다.
- 기존 병목 계측에는 auth 경로가 별도 profile 로 드러나지 않아, "서비스가 느린지"와 "인증 조회가 느린지"를 구분하기 어려웠다.

### 적용한 변경

- `LoadTestStepProfiler` 를 도입해 서비스 메서드 안과 필터 경로에서도 동일한 형식의 sub-step profile 을 기록할 수 있게 했다.
- `JwtAuthenticationFilter` 에 `jwt.auth.resolve-user` profile 을 추가해 토큰 해석 후 인증 정보 결정을 별도 계측하도록 바꿨다.
- `CachedJwtUserDetailsService` 를 추가해 같은 username 의 JWT 인증 정보를 짧은 TTL 로 캐시하고, miss 시에만 `jwt.auth.load-user.db` profile 을 기록하도록 했다.
- `LoadTestMetricsSnapshot` / `LoadTestMetricsService` 에 `jwtAuthCacheHits`, `jwtAuthCacheMisses` 를 추가해 auth cache 효과를 k6 결과와 함께 저장하도록 했다.
- `CustomUserDetailsService` 와 `UserRepository` 는 인증용 최소 projection 으로 바꿔 miss 시에도 전체 `User` 엔티티를 읽지 않게 정리했다.

### 측정 결과

실행 전 참고 샘플:

- 실행 시각: `20260324-045437`
- artifact: `loadtest/results/soak-20260324-045437-server.json`
- 당시에는 auth 경로가 별도 profile 로 분리되지 않았다.
- 같은 조건의 short soak 에서 `dbPoolTimeouts = 15`, `maxThreadsAwaitingConnection = 197`

계측/최적화 후:

- 실행 시각: `20260324-050212`
- artifact: `loadtest/results/soak-20260324-050212-server.json`
- `jwtAuthCacheHits = 3820`
- `jwtAuthCacheMisses = 62`
- `jwt.auth.resolve-user sampleCount = 3882`
- `jwt.auth.resolve-user averageWallTimeMs = 11.12`
- `jwt.auth.resolve-user averageSqlStatementCount = 0.016`
- `jwt.auth.load-user.db sampleCount = 62`
- `jwt.auth.load-user.db averageWallTimeMs = 228.79`
- `jwt.auth.load-user.db averageSqlExecutionTimeMs = 0.40`
- `jwt.auth.load-user.db averageSqlStatementCount = 0.98`
- `dbPoolTimeouts = 435`
- `maxActiveConnections = 20`, `maxThreadsAwaitingConnection = 200`

### 해석

- 인증 요청 대부분은 `3820 / 3882` 수준으로 캐시 hit 가 되었고, 평균 SQL 문 수도 `jwt.auth.resolve-user = 0.016` 까지 낮아졌다.
- 반면 miss 경로의 `averageWallTimeMs = 228.79` 에 비해 `averageSqlExecutionTimeMs = 0.40` 이 매우 작다.
- 즉 auth miss 비용의 대부분은 느린 SQL 이 아니라 "커넥션을 얻기 전 대기"다. 이 결과로 남은 병목이 query plan 보다 pool 포화 쪽이라는 점이 더 명확해졌다.
- 이번 short soak 샘플에서는 전체 `dbPoolTimeouts` 가 여전히 높았고 오히려 더 나쁘게 나왔다. 따라서 auth cache 하나만으로 전체 한계점이 올라갔다고 해석하면 안 된다.
- 다만 이번 변경으로 "인증 경로가 실제로 얼마나 DB를 치는가"와 "miss 가 왜 비싼가"를 수치로 볼 수 있게 됐고, 다음 최적화 우선순위를 더 정확히 좁힐 수 있게 됐다.

### 검증

targeted Gradle 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.security.CachedJwtUserDetailsServiceTest" \
  --tests "com.effectivedisco.loadtest.LoadTestMetricsServiceTest" \
  --tests "com.effectivedisco.loadtest.LoadTestMetricsControllerTest" \
  --tests "com.effectivedisco.service.PostServiceTest" \
  --tests "com.effectivedisco.service.PostCreateOptimizationIntegrationTest" \
  --tests "com.effectivedisco.service.NotificationServiceTest" \
  --tests "com.effectivedisco.loadtest.LoadTestActionControllerTest"
```

short soak:

```bash
SOAK_FACTOR=1 SOAK_DURATION=10s WARMUP_DURATION=5s SAMPLE_INTERVAL_SECONDS=2 \
  BASE_URL=http://localhost:18080 ./loadtest/run-bbs-soak.sh
```

결과:

- targeted Gradle 테스트 통과
- short soak 에서 새 metric (`jwtAuthCacheHits/Misses`) 과 새 bottleneck profile (`jwt.auth.resolve-user`, `jwt.auth.load-user.db`) 이 정상적으로 기록됐다
- 같은 실행에서도 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0`

### 남은 과제

- `jwt.auth.load-user.db` miss 자체는 가벼워졌지만, miss 시점의 wall time 대부분이 커넥션 대기이므로 다음 단계는 `pool 포화를 유발하는 나머지 경로`를 더 줄이는 것이다.
- 특히 현재 short soak 기준으로는 `post.list averageWallTimeMs = 30.97` 가 여전히 가장 큰 profile 이고, pool timeout 은 전체 혼합 부하에서 계속 먼저 드러난다.
- 다음 경계점 재측정과 장시간 soak 에서는 `jwtAuthCacheHits/Misses` 를 함께 보며 인증 경로가 더 이상 주병목이 아닌지 확인해야 한다.

## 2026-03-24 `Hikari maximumPoolSize` 실험과 기본값 조정

상태: 완료

### 배경

- 현재 로컬 환경은 `Ryzen 7 7840U`, `8코어 16스레드` 이고, loadtest 프로필 기본 Hikari 최대 pool 크기는 `20` 이었다.
- 이전 반복 ramp-up / short soak 에서는 `maxActiveConnections=20`, `maxThreadsAwaitingConnection≈200`, `dbPoolTimeouts>0` 가 계속 관측됐다.
- 따라서 "몇으로 올릴 것인가"를 추정이 아니라 반복 실험으로 결정할 필요가 있었다.

### 실험 조건

- 실행 시각: `20260324-051234`
- 결과 루트: `loadtest/results/pool-sweep-20260324-051234`
- 대상 pool 크기: `20`, `24`, `28`, `32`
- 각 pool 크기마다 `3회` 반복
- 공통 조건:
  - `SOAK_FACTOR=1`
  - `WARMUP_DURATION=5s`
  - `SOAK_DURATION=10s`
  - `SAMPLE_INTERVAL_SECONDS=2`
  - `BASE_URL=http://localhost:18080`

### 결과

중앙값 기준:

- `20`
  - `p95 = 1473.98ms`
  - `p99 = 1833.75ms`
  - `unexpected_response_rate = 0.0285`
  - `dbPoolTimeouts = 264`
  - `maxThreadsAwaitingConnection = 200`
- `24`
  - `p95 = 470.05ms`
  - `p99 = 584.89ms`
  - `unexpected_response_rate = 0.0000`
  - `dbPoolTimeouts = 0`
  - `maxThreadsAwaitingConnection = 194`
- `28`
  - `p95 = 429.99ms`
  - `p99 = 539.04ms`
  - `unexpected_response_rate = 0.0000`
  - `dbPoolTimeouts = 0`
  - `maxThreadsAwaitingConnection = 191`
- `32`
  - `p95 = 438.41ms`
  - `p99 = 549.32ms`
  - `unexpected_response_rate = 0.0000`
  - `dbPoolTimeouts = 0`
  - `maxThreadsAwaitingConnection = 188`

### 해석

- `20` 은 현재 시나리오에서 확실히 부족하다.
- `24`, `28`, `32` 는 모두 timeout 없이 버텼지만, `28` 이 `p95/p99` 기준으로 가장 좋았다.
- `32` 는 waiting threads 가 약간 줄었지만 지연시간은 `28` 보다 소폭 나빠졌다.
- 즉 현재 로컬 기준으로는 "조금 더 큰 pool" 이 아니라 `28` 이 가장 균형이 좋다.

### 적용한 설정

- `application-loadtest.yml` 의 기본 `maximum-pool-size` 를 `20 -> 28` 로 올렸다.
- 환경 변수 `APP_LOAD_TEST_DB_POOL_MAX_SIZE` 로는 계속 override 가능하게 유지했다.

### 남은 과제

- 이 값은 현재 로컬 장비 + 현재 workload 기준값이다.
- 장시간 soak 와 더 높은 ramp-up 에서도 `28` 이 최선인지 다시 확인해야 한다.
- 대용량 데이터셋으로 넘어가면 같은 sweep 를 다시 돌려야 한다.

## 2026-03-24 `sub-1.0` 안정 구간 탐색 + PostgreSQL wait/slow-query 계측 추가

상태: 완료

### 배경

- `pool=28` 재측정 이후에도 `1.0x` 가 `4/5 PASS`, `1.25x` 가 `0/4 PASS` 였다.
- 따라서 다음 단계는 "1.0 미만 어디까지가 실제 안정 구간인가"를 반복 실험으로 좁히는 것이었다.
- 동시에 기존 `server-metrics` 는 `maxActiveConnections`, `maxThreadsAwaitingConnection`, `dbPoolTimeouts` 까지만 보여줘서, "왜 커넥션이 오래 점유되는지"를 PostgreSQL 내부에서 바로 읽기 어려웠다.

### 적용한 변경

- [run-bbs-sub-stability.sh](/home/admin0/effective-disco/loadtest/run-bbs-sub-stability.sh) 를 추가했다.
- 이 스크립트는 `run-bbs-ramp-up.sh` 를 반복 실행하고, 각 run 의 raw `ramp-up-*.tsv` 를 factor 기준으로 재집계해 `PASS/LIMIT/FAIL` 분포와 `highest stable factor` 를 별도 리포트로 남긴다.
- `LoadTestMetricsSnapshot` 에 `postgresSnapshot` 을 추가했다.
- `PostgresLoadTestInspector` 는 `pg_stat_activity` 를 조회해 아래 항목을 `server-metrics` 와 함께 저장한다.
  - `waitingSessions`
  - `lockWaitingSessions`
  - `longRunningTransactions`
  - `longRunningQueries`
  - `longestTransactionMs`
  - `longestQueryMs`
  - `topWaitEvents`
  - `slowActiveQueries`
- `application-loadtest.yml` 에 PostgreSQL `ApplicationName` 을 고정해 loadtest 인스턴스가 만든 세션만 `pg_stat_activity` 에서 구분할 수 있게 했다.
- idle 세션의 `ClientRead` 는 실제 병목이 아니므로, wait 계측은 `state <> 'idle'` 또는 `state = 'active'` 조건으로 필터링했다.

### 검증

targeted Gradle 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.loadtest.LoadTestMetricsServiceTest" \
  --tests "com.effectivedisco.loadtest.LoadTestMetricsControllerTest" \
  --tests "com.effectivedisco.loadtest.PostgresLoadTestInspectorTest" \
  --tests "com.effectivedisco.security.CachedJwtUserDetailsServiceTest"
```

짧은 실제 sanity:

```bash
RUNS=1 STAGE_FACTORS=0.75,0.9 STOP_ON_HTTP_P99_MS=1200 \
  BROWSE_DURATION=5s HOT_POST_DURATION=5s SEARCH_DURATION=5s \
  WRITE_STAGE_ONE_DURATION=5s WRITE_STAGE_TWO_DURATION=5s \
  LIKE_ADD_DURATION=5s LIKE_REMOVE_DURATION=5s \
  BOOKMARK_MIXED_DURATION=5s FOLLOW_MIXED_DURATION=5s \
  BLOCK_MIXED_DURATION=5s NOTIFICATION_MIXED_DURATION=5s \
  BASE_URL=http://localhost:18081 ./loadtest/run-bbs-sub-stability.sh
```

결과:

- targeted Gradle 테스트 통과
- `curl -sf http://localhost:18081/internal/load-test/metrics` 기준 `postgresSnapshot.waitingSessions=0`, `lockWaitingSessions=0`, `slowActiveQueries=[]`
- [sub-stability-20260324-060133.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-060133.md) 기준 `0.75`, `0.9` 는 모두 `PASS`
- 같은 짧은 sanity 최종 `server-metrics` 에서 `dbPoolTimeouts=0`, `duplicateKeyConflicts=0`, `jwtAuthCacheHits=4203`, `jwtAuthCacheMisses=45`

### 해석

- 이번 변경은 경계점을 "올린" 최적화가 아니라, 다음 실험을 더 정확히 하기 위한 관측면 확장이다.
- `sub-1.0` 래퍼가 생겨서 이제 `0.75 / 0.85 / 0.9 / 0.95 / 1.0` 구간을 반복 실행하고, factor별 재현성 있는 안정 구간을 바로 집계할 수 있다.
- `postgresSnapshot` 으로는 이제 pool 포화가 생겼을 때 PostgreSQL 내부에서 실제 lock wait 인지, 장기 query 인지, 단순 active session 증가인지 같은 실행 기준으로 같이 볼 수 있다.
- 짧은 sanity 에서 `0.75`, `0.9` 가 모두 `PASS` 였지만, 이는 `RUNS=1` 확인이므로 아직 안정 구간 결론으로 쓰면 안 된다.

### 남은 과제

- `RUNS=5` 이상으로 `0.75 / 0.85 / 0.9 / 0.95 / 1.0` 반복 탐색을 돌려 실제 `highest stable factor` 를 확정해야 한다.
- 확정된 factor 로 `30분 -> 1시간 -> 2시간` soak 를 재개해야 한다.
- PostgreSQL wait/slow-query 스냅샷에서 의미 있는 `Lock`, `LWLock`, 장기 active query 가 관측되면 그 경로를 다음 최적화 대상으로 삼아야 한다.

## 2026-03-24 `sub-1.0` 반복 측정 결과

상태: 완료

### 실행 조건

- `APP_LOAD_TEST_DB_POOL_MAX_SIZE=28`
- `RUNS=5`
- `STAGE_FACTORS=0.75,0.85,0.9,0.95,1.0`
- `STOP_ON_HTTP_P99_MS=800`
- 결과 디렉터리: `loadtest/results/sub-stability-20260324-062311`

### 결과

- [sub-stability-20260324-062311.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-062311.md) 기준 `highest stable factor = n/a`
- `0.75`: `4 PASS / 0 LIMIT / 1 FAIL`
- `0.85`: `1 PASS / 2 LIMIT / 1 FAIL`
- `0.9`: `0 PASS / 0 LIMIT / 1 FAIL`
- `0.75` 실패 런은 `unexpected_response_rate=0.0033`, `dbPoolTimeouts=144`, `p99=1350.56ms`
- `0.85` 는 `db-pool-timeout` LIMIT 와 `unexpected-response` FAIL 이 섞여 있었고, 최대 `p99=980.46ms`, 최대 `dbPoolTimeouts=11`
- 모든 run 에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread` mismatch `0`

### 해석

- `pool=28` 로 올린 뒤에도 현재 로컬 환경에서는 `0.75~1.0` 구간에 재현성 있게 안정적인 factor 가 없다.
- 따라서 지금 문제는 단순히 "pool 값을 더 키울지" 가 아니라, 짧은 시간에도 간헐적으로 `dbPoolTimeouts` 와 `unexpected_response_rate` 를 폭증시키는 환경 변동성 또는 남은 connection 점유 경로를 더 파는 것이다.
- 특히 `0.75` 에서조차 한 번은 실패했기 때문에, 이 결과는 soak 기준 factor 를 아직 정하지 못했다는 뜻이다.

### 남은 과제

- `0.5 / 0.6 / 0.7` 구간으로 더 내려가 실제 안정 기준선을 다시 찾는다.
- 같은 반복 측정 중 `postgresSnapshot` 을 더 길게 저장해 `wait_event`, `longestQueryMs`, `slowActiveQueries` 가 실패 런에서 어떻게 튀는지 비교한다.
- 안정 factor 가 확정되기 전까지 `30분 -> 1시간 -> 2시간` soak 는 보류한다.

## 2026-03-24 `0.5~0.7` 반복 측정 + PostgreSQL wait 원인 수집

상태: 완료

### 실행 조건

- `APP_LOAD_TEST_DB_POOL_MAX_SIZE=28`
- `RUNS=5`
- `STAGE_FACTORS=0.5,0.6,0.7`
- 결과 디렉터리: `loadtest/results/sub-stability-20260324-064643`
- 추가 focused run:
  - `SOAK_FACTOR=0.7`
  - `SOAK_DURATION=30s`
  - `WARMUP_DURATION=5s`
  - `SAMPLE_INTERVAL_SECONDS=2`
  - artifact: `loadtest/results/soak-20260324-070318-metrics.jsonl`

### 결과

- [sub-stability-20260324-064643.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-064643.md) 기준 `highest stable factor = 0.6`
- `0.5`: `5 PASS / 0 LIMIT / 0 FAIL`
- `0.6`: `5 PASS / 0 LIMIT / 0 FAIL`
- `0.7`: `3 PASS / 1 LIMIT / 1 FAIL`
- `0.7` LIMIT 런은 `db-pool-timeout`, FAIL 런은 `unexpected-response` 였고, `dbPoolTimeouts=1~3`, `p99=677.19ms~685.37ms`
- 모든 run 에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread` mismatch `0`

### PostgreSQL wait 관찰

- focused `0.7` 런 [soak-20260324-070318.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-070318.md) 은 최종 스냅샷만 보면 `dbPoolTimeouts=0` 이지만, 타임라인 [soak-20260324-070318-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-070318-metrics.jsonl) 에서는 런 중 피크가 분명했다.
- 피크 구간에서 `currentActiveConnections=28`, `currentThreadsAwaitingConnection=177~185`, `waitingSessions=12`, `lockWaitingSessions=8`, `longestTransactionMs=101`, `longestQueryMs=47`
- 관측된 주요 wait 는 `Lock/transactionid`, `Lock/tuple`, `LWLock/WALWrite`, `IO/WALSync`, `ClientRead`
- `slowActiveQueries` 는 대부분 `posts + users` 목록 조회 쿼리와 `count(*) from posts ...` 검색/목록 count 쿼리였다.

### 해석

- `0.6` 이 현재 로컬 환경에서 첫 재현성 있는 안정 factor 다.
- `0.7` 부근부터는 정합성 문제가 아니라, read path 와 write path 가 함께 몰릴 때 PostgreSQL 내부의 lock/WAL pressure 가 생기고, 이 압력이 Hikari 대기열 증가로 바로 전파된다.
- 특히 `post.list` 자체는 이미 N+1 을 줄였지만, 아직도 wall time 이 가장 큰 profile 이고, 여기에 검색/목록 count 쿼리가 겹치면서 `0.7` 부근에서 한계가 나타난다.

### 남은 과제

- `0.6` 기준으로 `30분 -> 1시간 -> 2시간` soak 를 시작한다.
- 다음 최적화 대상은 `posts + users` 목록 조회의 남은 wall time 과 검색/목록 `count(*)` 경로다.
- lock/WAL pressure 가 실제로 어디서 시작되는지 보려면 PostgreSQL 쪽 `pg_stat_statements` 또는 더 긴 timeline 비교를 추가하는 것이 좋다.

## 2026-03-24 목록/검색 projection + countQuery 최적화

상태: 완료

### 배경

- [soak-20260324-070318-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-070318-metrics.jsonl) 기준 `0.7` 부근의 `slowActiveQueries` 는 대부분 두 부류였다.
  - `posts + users` 목록 본문 select
  - `count(*) from posts ...` 검색/목록 count 쿼리
- 특히 기존 목록 본문은 `author` 전체 엔티티를 끌고 와 `users` 컬럼 폭이 넓었고, 검색/태그 `Page count(*)` 도 `join users` / `join post_tags` fan-out 을 그대로 탔다.

### 적용한 변경

- [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - `PostListRow`, `PostTagRow`, `PostImageRow` projection 추가
  - latest / likes / comments / board / keyword / tag 전용 목록 query를 projection 기반으로 분리
  - 검색/태그 query는 `countQuery` 를 명시해 `join users` / `join post_tags` fan-out 을 줄였다
- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - `getPosts()` 가 `Page<Post>` 대신 `Page<PostListRow>` 를 읽고, 현재 페이지 ID 기준 태그/이미지 row 를 한 번씩만 가져와 DTO 조립
  - entity 기반 목록 변환은 작성자 게시물/초안처럼 필요한 경로에만 유지
- [PostResponse.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/dto/response/PostResponse.java)
  - raw-field 생성자를 추가해 목록 hot path 가 `Post` 엔티티를 다시 만들지 않게 정리
- [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java)
  - latest list statement count 상한을 `<=4` 로 강화
  - `board + keyword search` 에 대해 `totalElements` 와 statement count `<=5` 검증 추가

### 검증

targeted 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.service.PostListOptimizationIntegrationTest" \
  --tests "com.effectivedisco.service.PostServiceTest" \
  --tests "com.effectivedisco.controller.web.SearchWebControllerTest"
```

결과:

- targeted 테스트 통과
- latest list / board+keyword search statement count 상한 통과

focused `0.7` 재측정:

- 워밍업 직후 1차: [soak-20260324-072046.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-072046.md)
  - `p95=887.34ms`, `p99=1126.08ms`, `dbPoolTimeouts=20`
- 같은 인스턴스 재측정 2차: [soak-20260324-072247.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-072247.md)
  - `p95=763.21ms`, `p99=980.04ms`, `dbPoolTimeouts=10`

참고:

- `0.6` baseline soak 는 [soak-20260324-071213-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-071213-metrics.jsonl) 기준 약 6분 시점에 이미 `dbPoolTimeouts=152` 와 기존 느린 query 패턴이 재현돼, 같은 failure mode 확인 후 새 코드 검증으로 전환했다.

### 해석

- 쿼리 모양 자체는 분명히 좋아졌다.
  - `slowActiveQueries` 에서 예전 `join users ... a1_0.id,a1_0.bio,...` 형태 대신 `username` 중심의 얇은 projection select 가 관측됐다.
  - 검색/태그 count 도 `count(p1_0.id)` 중심으로 바뀌었고, 불필요한 join fan-out 은 줄었다.
- 하지만 현재 로컬 환경에서 `0.7` macro latency 는 아직 안정화되지 않았다.
  - timeout 과 wait 는 줄지 않았고, [soak-20260324-072247-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-072247-server.json) 기준 `dbPoolTimeouts=10`, `maxThreadsAwaitingConnection=190`
  - `slowActiveQueries` 는 여전히 목록 본문 select 와 검색/태그 count 쿼리를 가리킨다.
- 결론적으로 이번 변경은 `query shape 최적화` 단계로는 유효했지만, 다음 단계는 더 공격적인 read-path 축소 또는 PostgreSQL 실행계획 분석이다.

### 남은 과제

- `0.6` soak 를 새 코드 기준으로 다시 시작한다.
- `post.list` 와 검색/태그 count 경로에 대해 인덱스/실행계획(`EXPLAIN ANALYZE`, `pg_stat_statements`)을 본다.
- 필요하면 목록 전용 API 에서 `content` 칼럼 자체를 제외하는 더 얇은 projection 도 검토한다.

## 2026-03-24 `0.6 / 30분` soak 재검증 + PostgreSQL 실행계획 분석

상태: 완료

### 배경

- 앞선 `0.5~0.7` 반복 측정에서는 `0.6` 이 첫 안정 factor 로 나왔지만, 이는 짧은 ramp-up 기준이었다.
- 목록/검색 projection 최적화 이후에도 실제 장시간 soak 에서 같은 결론이 유지되는지 다시 확인할 필요가 있었다.
- 동시에 `slowActiveQueries` 가 계속 `post.list` 와 `count(*)` 경로를 가리켰기 때문에, PostgreSQL 실행계획 수준에서 원인을 더 구체적으로 확인해야 했다.

### 실행 결과

장시간 soak:

- 실행 시각: `20260324-073142`
- artifact: [soak-20260324-073142.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-073142.md)
- `soak_factor = 0.6`
- `soak_duration = 30m`
- `status = FAIL`
- `http p95 = 979.75ms`
- `http p99 = 1289.36ms`
- `unexpected_response_rate = 0.0036`
- `dbPoolTimeouts = 4752`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 194`
- SQL snapshot [soak-20260324-073142-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260324-073142-sql.tsv) 기준 `duplicate row = 0`, `postLike/comment/unread mismatch = 0`

최종 서버 프로파일:

- artifact: [soak-20260324-073142-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-073142-server.json)
- `post.list averageWallTimeMs = 93.75`
- `post.list averageSqlExecutionTimeMs = 88.67`
- `post.list averageSqlStatementCount = 4.81`
- `notification.read-all.summary averageWallTimeMs = 51.42`
- `notification.store averageWallTimeMs = 12.65`
- `jwt.auth.load-user.db averageWallTimeMs = 239.48`
- `jwt.auth.load-user.db averageSqlExecutionTimeMs = 0.68`

PostgreSQL wait 타임라인:

- artifact: [soak-20260324-073142-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-073142-metrics.jsonl)
- `max waitingSessions = 9`
- `max lockWaitingSessions = 7`
- `max longestQueryMs = 154`
- `max longestTransactionMs = 225`
- 상위 wait: `Client/ClientRead`, `Lock/transactionid`, `Lock/tuple`, `IO/WALSync`, `LWLock/WALWrite`
- 상위 slow query: `post.list`, `board + keyword count(*)`, `tag count(*)`

### PostgreSQL 실행계획 분석

사전 확인:

- `pg_stat_statements` 는 이 로컬 PostgreSQL 에 활성화되어 있지 않았다.
- `shared_preload_libraries` 가 비어 있었고, `pg_extension` 에도 `pg_stat_statements` 가 없었다.
- 따라서 이번 분석은 `EXPLAIN (ANALYZE, BUFFERS)` 와 현재 인덱스 상태 기준으로 진행했다.

데이터 규모:

- `posts = 98,692`
- `post_tags = 203,046`
- `dev/free/qna` 게시판은 각각 약 `32.8k` row 규모

실행계획 핵심:

- `board + keyword` 목록 본문 query
  - 실행 시간: 약 `93.7ms`
  - `idx_post_board_draft` 로 board slice 는 줄였지만, `%keyword%` 필터 때문에 여전히 수만 건을 heap scan/filter 한다.
  - `created_at desc` 정렬은 인덱스 지원 없이 top-N sort 로 끝난다.
- `board + keyword count(*)`
  - 실행 시간: 약 `98.5ms`
  - explicit `countQuery` 로 query shape 는 개선됐지만, 결국 같은 `posts` 범위를 다시 스캔한다.
  - 병목은 `users` join fan-out 이 아니라 `title/content` substring filter 자체다.
- `tag count(*)`
  - 실행 시간: 약 `71.6ms`
  - `post_tags` 를 거의 전범위로 읽고 `HashAggregate` 한 뒤 `posts` PK lookup 을 수행한다.
  - 현재 PK 방향 `(post_id, tag_id)` 만으로는 `tag -> posts` 탐색이 비효율적이다.

인덱스 상태 관찰:

- `posts` 는 `idx_post_board_draft (board_id, draft)` 와 `idx_post_user (user_id)` 정도만 존재한다.
- `post_tags` 는 PK `(post_id, tag_id)` 만 있고 `tag_id` 선행 인덱스가 없다.
- 즉 현재 실행계획 기준으로는 아래 두 인덱스가 다음 후보다.
  - `posts(board_id, draft, created_at desc)`
  - `post_tags(tag_id, post_id)`

### 해석

- 최신 코드 기준으로는 `0.6` 도 장시간 soak 안정 구간이 아니다.
- 정합성은 계속 유지됐으므로, 지금 문제는 race condition 이 아니라 `read-heavy query + count(*) + lock/WAL pressure` 가 만든 커넥션 점유 시간이다.
- `post.list` 는 statement fan-out 은 이미 줄었지만, 이제는 한 번의 SQL 실행 시간 자체가 크다.
- `jwt.auth.load-user.db` 는 평균 wall time 이 `239ms` 수준이지만 SQL 시간은 `0.68ms` 수준이므로, auth miss 역시 느린 query 가 아니라 커넥션 대기 전파의 결과로 보는 편이 맞다.

### 남은 과제

- `posts(board_id, draft, created_at desc)` 인덱스를 추가해 최신 목록 정렬 비용을 줄인다.
- `post_tags(tag_id, post_id)` 인덱스를 추가해 태그 count/list 의 full scan 성격을 줄인다.
- `%keyword%` 검색을 유지할 계획이면 `pg_trgm` 또는 full-text search 방향을 별도로 검토한다.
- 목록/검색 응답에서 `content` 칼럼이 정말 필요한지 다시 검토한다.

## 2026-03-24 PostgreSQL `FTS + pg_trgm` 도입 설계

상태: 완료

요약:

- 검색 병목은 `title/content/username` 을 모두 `%keyword%` 로 처리하는 현재 query shape 에 있었다.
- `title/content` 는 문서 검색에 가까워 FTS 가 맞고, `username` 은 substring semantics 유지가 중요해 trigram-backed LIKE 가 더 적합했다.
- 따라서 `FTS only` 나 `trigram only` 가 아니라 `FTS + pg_trgm` 하이브리드를 채택했다.

구현 포인트:

- PostgreSQL 에서는 [PostRepositoryImpl](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java) 이 native SQL 로 `title/content` FTS + `username` LIKE 를 수행한다.
- H2 테스트 프로필에서는 같은 의미의 fallback SQL 로 동작시켜 CI 회귀 검증을 유지한다.
- 인프라 초기화는 [PostgresSearchInfrastructureInitializer](/home/admin0/effective-disco/src/main/java/com/effectivedisco/config/PostgresSearchInfrastructureInitializer.java) 가 맡는다.

상세 근거:

- 별도 설계 문서 [postgres-search-strategy.md](/home/admin0/effective-disco/docs/postgres-search-strategy.md) 참조

## 2026-03-24 PostgreSQL 검색 query shape rewrite + 재측정

상태: 완료

### 배경

- `FTS + pg_trgm` 자체는 도입했지만, 초기 구현은 `FTS OR username LIKE` 를 한 조건으로 묶은 형태였다.
- 그 상태의 `EXPLAIN ANALYZE` 에서는 PostgreSQL 이 `idx_posts_search_fts` 를 핵심 플랜으로 쓰지 못했고, `board/draft` 범위를 먼저 잡은 뒤 FTS/LIKE 를 필터링하는 경향이 남아 있었다.
- 실제 측정도 이를 뒷받침했다.
  - [board-keyword-list.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-fts/board-keyword-list.txt): `65.411ms`
  - [board-keyword-count.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-fts/board-keyword-count.txt): `155.216ms`
  - 검색 집중 soak [soak-20260324-085547.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-085547.md): `p95=1827.55ms`, `p99=1984.91ms`, `unexpected_response_rate=0.1274`, `dbPoolTimeouts=503`
- 즉 기술 선택은 맞았지만, 플래너가 그 기술을 제대로 활용하지 못하는 `query shape` 문제가 남아 있었다.

### 원인

- `title/content FTS` 와 `username LIKE` 를 하나의 `OR` predicate 로 묶으면, PostgreSQL 은 두 경로를 독립적으로 최적화하기보다 큰 `posts` 범위를 먼저 잡고 뒤에서 필터링하는 선택을 하기 쉬웠다.
- 특히 최신순 정렬과 게시판 조건이 함께 있을 때, 플래너는 `board/draft/created_at` 쪽 접근을 선호했고 FTS 인덱스는 보조 필터 수준으로 밀렸다.
- 결과적으로 `FTS + pg_trgm` 를 넣어도 본문/카운트 쿼리의 핵심 비용은 충분히 줄지 않았다.

### 적용한 변경

- [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  - `FTS branch` 와 `username trigram branch` 를 분리
  - 두 branch 에서 `post id` 만 먼저 가져오고 `UNION` 으로 결합
  - 중복 `post id` 는 dedup 한 뒤 본문 projection / `count(*)` 계산에 재사용
- 이 구조로 PostgreSQL 이 `idx_posts_search_fts` 와 `idx_users_username_trgm` 를 독립적으로 고려한 뒤, 결과 id 집합만 합치도록 유도했다.
- 검색 의미는 바꾸지 않았다.
  - `title/content`: FTS
  - `username`: substring semantics 유지

### EXPLAIN 재측정

실행 결과 디렉터리:

- [explain-20260324-rewrite](/home/admin0/effective-disco/loadtest/results/explain-20260324-rewrite)

전후 비교:

- `board + keyword list`
  - 이전: `65.411ms`
  - 이후: `14.852ms`
- `board + keyword count(*)`
  - 이전: `155.216ms`
  - 이후: `6.334ms`
- `tag count(*)`
  - 이전: `12.660ms`
  - 이후: `13.781ms`

실행계획 관찰:

- [board-keyword-list.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-rewrite/board-keyword-list.txt)
  - `idx_posts_search_fts` `Bitmap Index Scan` 사용
  - `idx_users_username_trgm` `Bitmap Index Scan` 사용
  - branch 결과는 `HashAggregate` 로 dedup 후 본문 row 를 조립
- [board-keyword-count.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-rewrite/board-keyword-count.txt)
  - `idx_posts_search_fts` 사용
  - `username` branch 는 trigram index 후보로 남은 채 결과 id 를 합산
- [tag-count.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-rewrite/tag-count.txt)
  - `idx_post_tags_tag_post` `Index Only Scan` 유지

### 검색 집중 soak 재측정

실행 결과:

- [soak-20260324-101110.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-101110.md)
- [soak-20260324-101110-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-101110-server.json)

전후 비교:

- `http p95`: `1827.55ms -> 437.57ms`
- `http p99`: `1984.91ms -> 639.39ms`
- `unexpected_response_rate`: `0.1274 -> 0.0001`
- `dbPoolTimeouts`: `503 -> 1`

서버 프로파일:

- `post.list averageWallTimeMs = 76.11`
- `post.list averageSqlExecutionTimeMs = 72.42`
- `post.list averageSqlStatementCount = 4.67`
- `jwt.auth.load-user.db averageWallTimeMs = 149.62`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 106`

해석:

- 검색 query shape rewrite 는 검색 집중 부하에서 체감 가능한 개선을 만들었다.
- status 는 여전히 `FAIL` 이지만, 이전처럼 search path 자체가 시스템을 무너뜨리는 수준은 아니었다.
- 남은 hot path 는 검색 branch 자체보다 여전히 `post.list` SQL 실행 시간이다.

### 반복 ramp-up 재측정

실행 결과:

- [sub-stability-20260324-101158.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-101158.md)
- [sub-stability-20260324-101158-aggregate.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-101158-aggregate.tsv)

최종 결과:

- `0.6`: `5/5 PASS`, max `p99=447.57ms`
- `0.7`: `5/5 PASS`, max `p99=497.31ms`
- `0.8`: `5/5 PASS`, max `p99=681.46ms`
- `highest stable factor = 0.8`
- 전 런 `dbPoolTimeouts=0`, `unexpected_response_rate=0.0000`

비교 기준:

- 이전 [sub-stability-20260324-085905.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-085905.md) 에서는 `0.6` 도 `5/5 FAIL`, `highest stable factor = n/a`

### 해석

- 이번 단계의 핵심은 "FTS/pg_trgm 도입" 자체보다 "`OR` 를 `branch + UNION/ID dedup` 으로 바꿔서 플래너가 인덱스를 실제로 쓰게 만든 것" 이다.
- query shape 가 바뀐 뒤에는 검색 `list/count` 가 실제로 빨라졌고, mixed ramp-up 안정 factor 도 `n/a` 에서 `0.8` 로 올라갔다.
- 즉 이번 개선은 마이크로 쿼리 개선과 매크로 안정성 회복 둘 다 확인된 드문 사례다.

### 남은 과제

- 새 기준 `0.8` 로 `30분 -> 1시간 -> 2시간` soak 를 다시 돌린다.
- 검색 병목은 많이 줄었으므로, 이후 남는 병목은 `post.list` 본문 SQL 자체와 mixed write 경로 경합인지 다시 분리해서 본다.

## 2026-03-24 `0.8 / 30분` soak 재검증

상태: 완료

### 배경

- 검색 query shape rewrite 이후 반복 ramp-up [sub-stability-20260324-101158.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-101158.md) 에서는 `0.6/0.7/0.8` 이 모두 `5/5 PASS` 였다.
- 하지만 이 결과는 짧은 stage 기반 반복 측정이므로, `0.8` 이 실제 장시간 mixed load 안정 구간인지 별도 soak 로 확인할 필요가 있었다.

### 실행 결과

실행 artifact:

- [soak-20260324-104517.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517.md)
- [soak-20260324-104517-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517-server.json)
- [soak-20260324-104517-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517-metrics.jsonl)
- [soak-20260324-104517-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517-sql.tsv)

측정값:

- `soak_factor = 0.8`
- `soak_duration = 30m`
- `status = FAIL`
- `http p95 = 1236.62ms`
- `http p99 = 1721.70ms`
- `unexpected_response_rate = 0.0031`
- `dbPoolTimeouts = 4707`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 200`

정합성:

- `duplicateKeyConflicts = 0`
- `relationDuplicateRows = 0`
- `postLikeMismatchPosts = 0`
- `postCommentMismatchPosts = 0`
- `unreadNotificationMismatchUsers = 0`

### 서버 프로파일

최종 병목 프로파일:

- `post.list averageWallTimeMs = 65.84`
- `post.list averageSqlExecutionTimeMs = 61.76`
- `post.list averageSqlStatementCount = 4.81`
- `notification.read-all.summary averageWallTimeMs = 58.81`
- `notification.read-all.summary.count averageWallTimeMs = 11.80`
- `notification.store averageWallTimeMs = 14.68`
- `jwt.auth.load-user.db averageWallTimeMs = 222.12`

중간 타임라인 특징:

- 초기 몇 분은 `dbPoolTimeouts=0` 이었지만, 약 6분 이후 timeout 이 누적되기 시작했다.
- 종료 시점에는 `dbPoolTimeouts=4707` 까지 증가했다.
- `maxThreadsAwaitingConnection` 은 `200` 까지 도달했다.

### PostgreSQL wait 관찰

장시간 타임라인에서 반복적으로 관측된 wait:

- `LWLock/WALWrite`
- `IO/WALSync`
- `Lock/transactionid`
- `Lock/tuple`

동시에 `slowActiveQueries` 상위에는 계속 아래가 남았다.

- 목록 본문 `post.list`
- 게시판/전체 목록 `count(*)`
- 태그 기반 `count(*)`

즉 검색 query rewrite 로 검색 branch 는 많이 좋아졌지만, 장시간 mixed soak 에서는 `post.list` 본문 SQL + 알림 read/write + WAL/lock pressure`가 다시 전체 pool을 잠식했다.

### 해석

- 이번 결과는 "`0.8` 이 반복 ramp-up 안정 구간"과 "`0.8` 이 장시간 soak 안정 구간"은 다른 문제라는 점을 분명하게 보여준다.
- 즉 검색 query shape rewrite 는 짧은 반복 부하와 검색 집중 부하 개선에는 성공했지만, 장시간 mixed soak 를 통과할 만큼 시스템 전체 병목을 제거하진 못했다.
- 현재 단계의 결론은 이렇다.
  - 검색 path rewrite: 성공
  - `0.8` ramp-up 안정화: 성공
  - `0.8 / 30분 soak` 안정화: 실패

### 남은 과제

- `0.7` 이하에서 다시 `30분 -> 1시간 -> 2시간` soak 를 잡아 실제 장시간 안정 구간을 다시 확정한다.
- `post.list` 본문 SQL 실행 시간을 더 줄인다. 특히 목록 응답에서 `content` 를 유지할지 재검토가 필요하다.
- `notification.read-all.summary` 와 `notification.store` 가 장시간 soak 에서 커지는 이유를 별도 분리 계측한다.
- 가능하면 PostgreSQL `pg_stat_statements` 를 활성화해 장시간 soak 기준 top query 누적 시간을 직접 확인한다.

## 2026-03-24 `post.list` row-width 축소 + 알림 read/write 경로 분리

상태: 완료

### 배경

- `0.8 / 30분` soak 실패 기준으로 여전히 가장 눈에 띄는 읽기 병목은 `post.list` 와 알림 read/write 경로였다.
- 당시 최종 프로파일은 다음과 같았다.
  - `post.list averageSqlExecutionTimeMs = 61.76`
  - `post.list averageSqlStatementCount = 4.81`
  - `notification.read-all.summary averageWallTimeMs = 58.81`
  - `notification.read-all.summary.count averageWallTimeMs = 11.80`
  - `notification.store averageWallTimeMs = 14.68`
- 코드 경로를 다시 확인해 보니, 이 병목은 "느린 한 쿼리"라기보다 "불필요한 row width / full list materialize / full count / 과도한 lock hold time"이 겹친 구조였다.

### 원인

- `post.list` projection 쿼리는 목록/검색 화면에서 쓰지 않는 `p.content` 전체를 계속 SELECT 했다.
- 검색 query shape 는 이미 개선됐지만, 결과 row 하나마다 긴 `content` 컬럼을 실어 나르면 row width 와 JDBC/ORM 복사 비용이 그대로 남는다.
- `/notifications` 웹 경로는 전체 알림 목록을 한 번에 projection 으로 읽고, 같은 트랜잭션 안에서 사용자 row lock 을 잡은 채 `markAllAsRead()` 를 수행했다.
- 즉 알림 페이지는 "전체 목록 materialize + 전체 DOM 렌더링 + read-all 상태 전환"이 한 번에 묶여 있었다.
- loadtest 전용 `/internal/load-test/actions/notifications/read-all` 도 `countByRecipient()` 로 전체 개수를 먼저 세고 나서 읽음 처리했다.
- 그 결과 `notification.read-all.summary` 는 상태 전환 비용만 보려는 의도와 달리, full count query 비용까지 같이 떠안고 있었다.

### 적용한 변경

- `PostRepository`, `PostRepositoryImpl` 의 hot path projection 에서 `content` 를 `'' AS content` 로 바꿨다.
- 즉 상세 페이지에서만 본문 전체를 읽고, 목록/검색/무한 스크롤에서는 빈 summary content 만 반환하도록 정리했다.
- `NotificationRepository` 에 `Slice<NotificationResponse>` projection 조회를 추가했다.
- `NotificationService.getAndMarkAllReadPage()` 를 도입해:
  - 현재 page batch 는 잠금 없이 `Slice` 로 읽고
  - 실제 read-all 상태 전환만 짧게 사용자 row lock 아래에서 수행하게 바꿨다.
- `NotificationResponse.asRead()` 를 추가해, 먼저 읽은 batch 도 read-all 직후 화면에서는 읽음 상태로 즉시 렌더링되게 했다.
- `NotificationWebController` 와 `notifications/list.html` 은 전체 목록 대신 page 단위 렌더링 + 이전/다음 링크로 변경했다.
- loadtest summary 경로는 `countByRecipient()` 를 제거하고, bulk update 가 실제로 전환한 unread row 수만 반환하도록 바꿨다.
- 즉 `notification.read-all.summary` 는 이제 "full count + transition" 이 아니라 "transition only" 에 가까운 계측값을 남긴다.

### 기대 효과

- `post.list`
  - row width 감소
  - JDBC result copy / Hibernate projection materialize 비용 감소
  - 목록/검색/스크롤 batch 당 네트워크 및 메모리 복사량 감소
- `notifications`
  - 웹 경로에서 full list materialize 제거
  - 사용자 row lock hold time 단축
  - full count query 제거
  - 알림 페이지 DOM 크기 상한 고정

### 검증

전체 회귀 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon
```

결과:

- 전체 테스트 통과
- `NotificationServiceTest` 에서:
  - page batch read 경로
  - counter drift fallback
  - loadtest summary 경로의 full count 제거
  를 검증했다.
- `NotificationWebControllerTest` 에서 `notificationPage` 모델 속성과 읽음 처리 회귀를 검증했다.
- `PostListOptimizationIntegrationTest` 에서 목록 응답의 `content` 가 비어 있는지 고정했다.

### 측정 메모

- 이번 턴에서는 새 코드 기준의 신뢰 가능한 short soak 비교값은 남기지 못했다.
- 이유는 기존 `18080` loadtest 인스턴스가 이미 떠 있는 상태라, 첫 측정이 예전 프로세스를 때렸고 새 코드 전용 비교 런으로 쓰기 부적절했기 때문이다.
- 따라서 이번 변경의 런타임 효과는 "구조적으로 병목 원인을 제거한 상태"까지 반영했고, 새 부하 수치는 다음 턴에서 깨끗한 loadtest 인스턴스로 재측정해야 한다.

### 남은 과제

- 새 코드 기준으로 `post.list`, `notification.read-all.summary`, `notification.store` short soak 재측정
- `0.7`, `0.8` 반복 ramp-up 및 soak 재실행
- 필요하면 `notification.store` 이후 `notification.read-all.page` 경로도 별도 k6 scenario 로 분리 측정
