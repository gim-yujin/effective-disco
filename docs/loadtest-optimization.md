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

## 2026-03-24 `clean loadtest short soak` 재측정

상태: 완료

### 배경

- 직전 최적화 섹션에서는 `post.list` row-width 축소와 알림 read-all 구조 분리를 적용했지만, 당시에는 `18080` 잔존 프로세스 때문에 새 코드 기준 short soak를 신뢰성 있게 남기지 못했다.
- 이번에는 새 `loadtest` 인스턴스를 `18081`에 별도로 띄우고 같은 short soak 프로파일로 다시 측정했다.

### 실행 결과

실행 artifact:

- [soak-20260324-115314.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-115314.md)
- [soak-20260324-115314-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-115314-server.json)
- [soak-20260324-115314-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-115314-metrics.jsonl)
- [soak-20260324-115314-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260324-115314-sql.tsv)

측정값:

- `soak_factor = 1`
- `soak_duration = 10s`
- `warmup_duration = 5s`
- `status = FAIL`
- `http p95 = 809.70ms`
- `http p99 = 988.87ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 192`

정합성:

- `duplicateKeyConflicts = 0`
- `relationDuplicateRows = 0`
- `postLikeMismatchPosts = 0`
- `postCommentMismatchPosts = 0`
- `unreadNotificationMismatchUsers = 0`

### 병목 프로파일

최종 프로파일:

- `post.list averageWallTimeMs = 62.13`
- `post.list averageSqlExecutionTimeMs = 58.45`
- `post.list averageSqlStatementCount = 4.81`
- `notification.read-all.summary averageWallTimeMs = 2.23`
- `notification.read-all.summary averageSqlExecutionTimeMs = 1.56`
- `notification.read-all.summary averageSqlStatementCount = 2.00`
- `notification.store averageWallTimeMs = 1.78`
- `notification.store averageSqlExecutionTimeMs = 1.29`
- `notification.store averageSqlStatementCount = 2.00`

### 직전 short soak 대비 변화

비교 기준:

- 이전 short soak artifact: `loadtest/results/soak-20260324-113324-server.json`
- 이번 clean short soak artifact: `loadtest/results/soak-20260324-115314-server.json`

변화:

- `post.list averageWallTimeMs`: `114.45 -> 62.13`
- `post.list averageSqlExecutionTimeMs`: `110.36 -> 58.45`
- `notification.read-all.summary averageWallTimeMs`: `2.68 -> 2.23`
- `notification.read-all.summary averageSqlStatementCount`: `3.00 -> 2.00`
- `notification.store averageWallTimeMs`: `1.86 -> 1.78`

### 해석

- `post.list`는 실제로 내려갔다. row-width 축소가 SQL 실행 시간까지 같이 줄인 것이 확인됐다.
- 알림 read-all summary는 full count 제거 효과가 명확했다. statement 수가 `3 -> 2`로 줄었고, 이제 `notification.read-all.summary.count` 같은 별도 count 병목이 남지 않는다.
- `notification.store`는 이미 비교적 가벼운 경로였기 때문에 개선 폭은 작지만, 여전히 `2 statement` 수준으로 유지됐다.
- 이번 short soak의 `FAIL`은 `unexpected_response_rate`나 `dbPoolTimeouts` 때문이 아니라 `k6 latency threshold` 초과 때문이다.
- 즉 이번 변경은 "timeout을 없애는 수준"까지는 아니어도, 최소한 `post.list`와 알림 read/write 경로를 실제로 가볍게 만들었다는 증거는 확보됐다.

### 남은 과제

- 이 상태로 `0.7`, `0.8` 반복 ramp-up 재측정
- 새 병목 순서 재확인
  - `post.list`가 여전히 1순위인지
  - 아니면 `createComment`, mixed scenario latency가 앞서는지
- 필요하면 `browse/search` 계열 threshold 초과 원인을 별도 분리 측정

## 2026-03-24 `0.7 / 0.8` clean 반복 ramp-up 재측정

상태: 완료

### 배경

- clean short soak [soak-20260324-115314.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-115314.md) 에서는 `dbPoolTimeouts=0`, `unexpected_response_rate=0` 이었고, 실패 원인도 latency threshold 뿐이었다.
- 하지만 short soak 한 번만으로는 재현성 있는 안정 구간이라고 결론낼 수 없으므로, 같은 최신 코드 기준으로 `0.7 / 0.8` 을 `RUNS=5` 반복 측정했다.

### 실행 결과

실행 artifact:

- [sub-stability-20260324-120211.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-120211.md)
- [sub-stability-20260324-120211.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-120211.tsv)
- [sub-stability-20260324-120211-aggregate.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-120211-aggregate.tsv)

최종 결과:

- `0.7`: `2/5 PASS`, `3/5 LIMIT`
- `0.8`: `0/2 PASS`, `1/2 LIMIT`, `1/2 FAIL`
- `highest stable factor = n/a`

세부 수치:

- `0.7` max `http p99 = 599.81ms`
- `0.7` max `dbPoolTimeouts = 1`
- `0.8` max `http p99 = 843.34ms`
- `0.8` max `dbPoolTimeouts = 3`
- `0.8` FAIL 런은 `unexpected_response_rate = 0.0001`

정합성:

- 전 런 `duplicateKeyConflicts = 0`
- 관계 중복 row = `0`
- `postLikeMismatchPosts = 0`
- `postCommentMismatchPosts = 0`
- `unreadNotificationMismatchUsers = 0`

### 해석

- clean short soak 에서 `dbPoolTimeouts=0` 이 나온 것은 사실이지만, 그 결과가 곧바로 반복 ramp-up 안정성으로 이어지지는 않았다.
- `0.7` 은 완전한 실패는 아니지만, `5/5 PASS` 를 만족하지 못했기 때문에 안정 구간으로 채택할 수 없다.
- `0.8` 은 여전히 명확한 불안정 구간이다. 이번에는 `unexpected-response` 까지 다시 나타났다.
- 즉 `post.list row-width 축소` 와 `notification read path` 개선은 실제로 병목을 줄였지만, 현재 로컬 환경에서 mixed 반복 부하의 재현성까지 회복시키기에는 아직 부족하다.

### 남은 과제

- `0.6 / 0.7` 을 다시 비교해서 soak 기준 factor 를 보수적으로 재확정
- `browse/search` 와 mixed write latency 를 더 잘게 분리해 threshold 초과 원인을 좁히기
- 필요하면 `post.list` 이후 hot path 를 다시 프로파일링해 다음 최적화 우선순위를 재정렬

## 2026-03-24 `0.6 / 0.65 / 0.7` clean 반복 ramp-up 재측정

상태: 완료

### 배경

- 직전 clean 반복 측정 [sub-stability-20260324-120211.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-120211.md) 에서 `0.7` 과 `0.8` 은 안정 구간으로 확정되지 못했다.
- 그래서 soak 기준 factor 를 다시 잡기 위해 `0.6 / 0.65 / 0.7` 하위 구간을 clean `18081` 인스턴스에서 `RUNS=5` 로 다시 측정했다.

### 실행 결과

실행 artifact:

- [sub-stability-20260324-122527.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-122527.md)
- [sub-stability-20260324-122527.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-122527.tsv)
- [sub-stability-20260324-122527-aggregate.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-122527-aggregate.tsv)

최종 결과:

- `0.6`: `3/5 PASS`, `2/5 LIMIT`
- `0.65`: `2/3 PASS`, `1/3 LIMIT`
- `0.7`: `0/2 PASS`, `2/2 LIMIT`
- `highest stable factor = n/a`

세부 수치:

- `0.6` max `http p99 = 584.11ms`
- `0.6` max `dbPoolTimeouts = 1`
- `0.65` max `http p99 = 667.72ms`
- `0.65` max `dbPoolTimeouts = 1`
- `0.7` max `http p99 = 626.59ms`
- `0.7` max `dbPoolTimeouts = 1`

정합성:

- 전 런 `duplicateKeyConflicts = 0`
- 관계 중복 row = `0`
- `postLikeMismatchPosts = 0`
- `postCommentMismatchPosts = 0`
- `unreadNotificationMismatchUsers = 0`

### 해석

- `0.7` 불안정은 다시 확인됐고, 이번에는 하위 구간으로 내려가도 `0.6` 이 `5/5 PASS` 를 만들지 못했다.
- 즉 현재 mixed 반복 부하에서 남아 있는 문제는 단순 threshold 초과가 아니라, 아주 작은 수준의 `dbPoolTimeouts` 도 재현성 있게 사라지지 않는다는 점이다.
- `0.65` 역시 아직 안정 구간으로 채택할 수 없다. `0.6` 이 안정적이지 않은 상태에서 `0.65` 는 더 위태롭다.
- 이번 하위 구간 탐색에서는 `unexpected-response` 가 사라졌기 때문에, 남은 실패 원인은 더 분명해졌다. 지금 병목은 정합성이 아니라 `Hikari timeout` 이다.

### 남은 과제

- `0.5 / 0.55 / 0.6` 으로 한 번 더 내려 실제 soak 기준 factor 를 확보
- `browse/search` 와 mixed write 를 더 쪼개서 어떤 시나리오가 가장 먼저 `db-pool-timeout` 을 만드는지 분리
- `post.list` 이후 hot path 재프로파일링 또는 환경 분리 필요성 검토

## 2026-03-24 scenario profile 분해 측정

상태: 완료

### 배경

- broad mixed 반복 측정에서는 `0.6` 조차 `5/5 PASS` 가 나오지 않았지만, 그 결과만으로는 어느 시나리오가 먼저 Hikari timeout 을 만드는지 분리할 수 없었다.
- 그래서 `bbs-load.js` 에 `SCENARIO_PROFILE` 개념을 넣고, `browse_search`, `write`, `relation_mixed`, `notification` 을 각각 독립적으로 켜고 끌 수 있게 바꿨다.
- 반복 실행용 래퍼로 `run-bbs-scenario-sub-stability.sh`, 여러 profile 을 한 번에 비교하는 `run-bbs-scenario-matrix.sh` 를 추가했다.

### 실행 결과

실행 artifact:

- [scenario-matrix-20260324-130059.md](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-130059.md)
- [scenario-matrix-20260324-130059.tsv](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-130059.tsv)

측정 조건:

- clean `loadtest` instance: `http://localhost:18081`
- `RUNS=5`
- `STAGE_FACTORS=0.5,0.55,0.6`
- `STOP_ON_HTTP_P99_MS=800`
- `STOP_ON_K6_THRESHOLD=0`

profile 별 결과:

- `browse_search`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- `write`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- `relation_mixed`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- `notification`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`

개별 aggregate:

- [browse_search aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search-20260324-130059/scenario-browse_search-20260324-130059/sub-stability-20260324-130059-aggregate.tsv)
- [write aggregate](/home/admin0/effective-disco/loadtest/results/scenario-write-20260324-130059/scenario-write-20260324-131251/sub-stability-20260324-131251-aggregate.tsv)
- [relation_mixed aggregate](/home/admin0/effective-disco/loadtest/results/scenario-relation_mixed-20260324-130059/scenario-relation_mixed-20260324-132825/sub-stability-20260324-132825-aggregate.tsv)
- [notification aggregate](/home/admin0/effective-disco/loadtest/results/scenario-notification-20260324-130059/scenario-notification-20260324-134015/sub-stability-20260324-134015-aggregate.tsv)

핵심 수치:

- `browse_search` max `p99`: `76.00ms`
- `write` max `p99`: `64.97ms`
- `relation_mixed` max `p99`: `167.65ms`
- `notification` max `p99`: `87.52ms`
- 전 profile 에서 `dbPoolTimeouts = 0`

### 해석

- broad mixed 에서만 timeout 이 났고, 단일 profile 은 모두 `0.6` 까지 안정적이었다.
- 즉 지금 문제는 "어느 하나의 시나리오가 혼자서 pool 을 터뜨린다"가 아니라, 여러 시나리오가 동시에 섞일 때 생기는 상호작용이다.
- 특히 `relation_mixed` 는 `maxThreadsAwaitingConnection` 이 `172` 까지 갔지만 timeout 없이 통과했다. 대기열만으로는 실패를 설명할 수 없고, read/write 동시 점유 시간이 겹칠 때가 문제라는 뜻이다.
- 다음 우선순위는 단일 profile 분해에서 한 단계 더 나아가 `browse_search + relation_mixed`, `browse_search + notification`, `write + relation_mixed` 같은 2-profile 조합 비교다.

### 남은 과제

- 2-profile 조합 matrix 추가
- `browse_search + relation_mixed` 를 첫 우선순위로 반복 측정
- broad mixed 와 2-profile 결과를 비교해 최소 재현 조합을 확정

## 2026-03-24 2-profile 조합 측정

상태: 완료

### 배경

- 단일 `scenario profile` 분해 결과로는 broad mixed 불안정성이 "시나리오 간 상호작용"이라는 점까지만 확인됐다.
- 그래서 이번에는 `SCENARIO_PROFILE=browse_search+relation_mixed` 처럼 `+` 로 묶인 2-profile 조합을 직접 실행해, 최소 재현 조합을 찾도록 `k6` gating 과 반복 측정 스크립트를 확장했다.
- 목표는 broad mixed 전체를 계속 돌리지 않고도, 어떤 조합에서 `dbPoolTimeouts` 와 `p99 급등`이 다시 나타나는지 좁히는 것이었다.

### 실행 결과

실행 artifact:

- [scenario-matrix-20260324-140610.md](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-140610.md)
- [scenario-matrix-20260324-140610.tsv](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-140610.tsv)

측정 조건:

- clean `loadtest` instance: `http://localhost:18081`
- `RUNS=5`
- `STAGE_FACTORS=0.5,0.55,0.6`
- `STOP_ON_HTTP_P99_MS=800`
- `STOP_ON_K6_THRESHOLD=0`

조합별 결과:

- `browse_search+relation_mixed`: `highest stable factor = 0.5`
- `browse_search+notification`: `highest stable factor = 0.6`
- `write+relation_mixed`: `highest stable factor = 0.6`

개별 aggregate:

- [browse_search+relation_mixed aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search+relation_mixed-20260324-140610/scenario-browse_search+relation_mixed-20260324-140610/sub-stability-20260324-140610-aggregate.tsv)
- [browse_search+notification aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search+notification-20260324-140610/scenario-browse_search+notification-20260324-141718/sub-stability-20260324-141718-aggregate.tsv)
- [write+relation_mixed aggregate](/home/admin0/effective-disco/loadtest/results/scenario-write+relation_mixed-20260324-140610/scenario-write+relation_mixed-20260324-142909/sub-stability-20260324-142909-aggregate.tsv)

핵심 수치:

- `browse_search+relation_mixed`
  - `0.5 = 5P/0L/0F`
  - `0.55 = 4P/1L/0F`
  - `0.6 = 1P/0L/3F`
  - max `p99 = 1126.48ms`
  - max `dbPoolTimeouts = 247`
- `browse_search+notification`
  - `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`
  - max `p99 = 154.64ms`
  - max `dbPoolTimeouts = 0`
- `write+relation_mixed`
  - `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`
  - max `p99 = 165.87ms`
  - max `dbPoolTimeouts = 0`

### 해석

- broad mixed 불안정성의 최소 재현 조합 후보는 `browse_search + relation_mixed` 로 좁혀졌다.
- 반대로 `browse_search + notification`, `write + relation_mixed` 는 같은 조건에서 안정적이었으므로, notification 경로나 write 경로 단독이 현재 주범은 아니라는 근거가 생겼다.
- 즉 현재 병목은 "read-heavy browse/search" 와 "relation mixed write" 가 동시에 DB pool 과 트랜잭션 점유 시간을 밀어 올릴 때 나타나는 상호작용이다.
- 이 결과로 다음 우선순위는 `browse_search` 를 `browse_board_feed`, `hot_post_details`, `search_catalog` 로 더 쪼개고, `relation_mixed` 도 `like`, `bookmark`, `follow`, `block` 으로 더 쪼개는 것이다.

### 남은 과제

- `browse_board_feed + relation_mixed`, `search_catalog + relation_mixed` 같이 더 작은 조합으로 재분해
- `relation_mixed` 내부에서도 `like add/remove` 와 `bookmark/follow/block` 을 나눠 최소 충돌 경로 확인
- broad mixed 재현 없이도 같은 실패를 만드는 최소 시나리오를 확정한 뒤 해당 조합만 집중 최적화

## 2026-03-24 세분화된 read/relation 조합 측정

상태: 완료

### 배경

- `browse_search + relation_mixed` 까지는 broad mixed 불안정성을 재현했지만, 이 수준으로는 여전히 read 경로가 너무 컸다.
- 그래서 `browse_search` 를 `browse_board_feed`, `hot_post_details`, `search_catalog` 로, `relation_mixed` 를 `like_mixed`, `bookmark_mixed`, `follow_mixed`, `block_mixed` 로 더 잘게 노출했다.
- 목적은 `원인`을 바로 단정하는 것이 아니라, broad mixed 를 가장 작게 재현하는 `최소 조건`을 찾는 것이었다.

### 구현

- [bbs-load.js](/home/admin0/effective-disco/loadtest/k6/bbs-load.js)
  - `browse_board_feed`, `hot_post_details`, `search_catalog`
  - `like_mixed`, `bookmark_mixed`, `follow_mixed`, `block_mixed`
  - 각 scenario 가 부모 profile(`browse_search`, `relation_mixed`)뿐 아니라 세분화된 component profile 에도 매핑되도록 확장
- [run-bbs-scenario-sub-stability.sh](/home/admin0/effective-disco/loadtest/run-bbs-scenario-sub-stability.sh)
  - 세분화된 component별 rate/VU 기본값 추가
  - `browse_board_feed+search_catalog+relation_mixed` 같은 조합도 그대로 해석

### 실행 결과

단일 read + relation 결과:

- `browse_board_feed+relation_mixed`
  - [suite](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+relation_mixed-20260324-174416/scenario-browse_board_feed+relation_mixed-20260324-174416/sub-stability-20260324-174416.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+relation_mixed-20260324-174416/scenario-browse_board_feed+relation_mixed-20260324-174416/sub-stability-20260324-174416-aggregate.tsv)
  - `0.5 = 4P/0L/1F`, `0.55 = 4P/0L/0F`, `0.6 = 4P/0L/0F`
- `search_catalog+relation_mixed`
  - [suite](/home/admin0/effective-disco/loadtest/results/scenario-search_catalog+relation_mixed-20260324-180052/sub-stability-20260324-180052.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-search_catalog+relation_mixed-20260324-180052/sub-stability-20260324-180052-aggregate.tsv)
  - `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`

두 read + relation 결과:

- `browse_board_feed+search_catalog+relation_mixed`
  - [suite](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/sub-stability-20260324-181449.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/sub-stability-20260324-181449-aggregate.tsv)
  - `0.5 = 1P/1L/3F`
  - `0.55 = 0P/0L/1F`
  - `highest stable factor = n/a`
  - max `p99 = 741.28ms`
  - max `dbPoolTimeouts = 30`

### 해석

- `search_catalog + relation_mixed` 만으로는 재현되지 않았고, `browse_board_feed + relation_mixed` 도 단발성 실패 1회를 제외하면 강한 재현 조합은 아니었다.
- 반면 `browse_board_feed + search_catalog + relation_mixed` 는 `0.5` 부터 broad mixed 와 유사한 실패 양상을 만들었다.
- 따라서 현재까지의 결론은 "root cause 확정"이 아니라, broad mixed 불안정성의 최소 재현 조건이 `feed + search + relation write` 수준까지 좁혀졌다는 것이다.
- 즉 relation write 자체보다, `feed` 와 `search` 의 read pressure가 함께 걸린 상태에서 relation write가 겹칠 때 DB pool 과 query latency가 동시에 밀린다.

### 남은 과제

- `browse_board_feed + search_catalog + like_mixed`
- `browse_board_feed + search_catalog + bookmark_mixed`
- `browse_board_feed + search_catalog + follow_mixed`
- `browse_board_feed + search_catalog + block_mixed`
- 위 4개 중 어느 relation write 가 첫 번째 실제 재현 쌍인지 확정한 뒤 그 경로만 집중 계측

## 2026-03-24 like_mixed 원인 추적 대상 확정

상태: 완료

### 배경

- 앞 단계에서 broad mixed 불안정성의 최소 재현 조건은 `browse_board_feed + search_catalog + relation_mixed` 까지 좁혀졌다.
- 하지만 여기서도 relation write 가 여전히 넓어서, 실제로 어떤 write 경로를 먼저 파야 하는지는 확정되지 않았다.
- 그래서 이번 단계는 `root cause` 를 바로 단정하는 대신, relation 단위를 더 쪼개 `원인 추적 1순위 대상`을 확정하는 데 집중했다.

### 실행 결과

- 대상 조합: `browse_board_feed+search_catalog+like_mixed`
- 실행 artifact:
  - [suite](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/sub-stability-20260324-211651.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/sub-stability-20260324-211651-aggregate.tsv)

aggregate:

- `0.5 = 5P/0L/0F`
- `0.55 = 3P/0L/2F`
- `0.6 = 1P/0L/2F`
- max `p99 = 1056.45ms`
- max `dbPoolTimeouts = 48`
- `unexpected-response` 재현

run detail 핵심:

- run02 `0.6`: `unexpected-response`, `dbPoolTimeouts = 3`
- run03 `0.6`: `unexpected-response`, `dbPoolTimeouts = 48`
- run04 `0.55`: `unexpected-response`, `dbPoolTimeouts = 3`
- run05 `0.55`: `unexpected-response`, `dbPoolTimeouts = 3`

정합성:

- `duplicateKeyConflicts = 0`
- 관계 중복 row = `0`
- `postLike/comment/unread mismatch = 0`

### 해석

- `feed + search + like add/remove` 조합은 broad mixed 불안정성과 같은 축의 실패를 충분히 재현한다.
- 이걸로 "좋아요가 root cause" 라고 단정할 수는 없지만, 최소한 `bookmark/follow/block` 보다 먼저 파야 할 추적 대상은 확정됐다.
- 즉 다음 계측/최적화 우선순위는 `browse_board_feed`, `search_catalog`, `like add/remove` 세 경로가 동시에 있을 때 어떤 query 와 wait 가 가장 먼저 커지는지 보는 것이다.
- `bookmark/follow/block` 까지 같은 수준으로 전부 돌려 비교하는 것은 그 다음 단계다. 이번 단계에서는 `like_mixed` 만으로도 이미 충분한 재현성이 확인됐기 때문에, 더 좁은 병목 분석으로 바로 들어가는 편이 효율적이다.

### 다음 액션

- `browse_board_feed + search_catalog + like_mixed` 전용 short soak
- 같은 조합에서 `post.list`, search count/query, like add/remove 의 `wall/sql/statement count/wait event` 비교
- PostgreSQL wait snapshot 과 앱 bottleneck profile 을 같은 시간축으로 비교

## 2026-03-24 like-focused 정밀 계측

상태: 완료

### 배경

- 앞 단계까지의 결론은 `browse_board_feed + search_catalog + like_mixed` 가 현재 가장 강한 최소 재현 조합이라는 것이었다.
- 하지만 이걸로 바로 `좋아요 write 가 원인`이라고 말할 수는 없었다.
- 필요한 것은 `feed/search` 와 `like add/remove` 중 어느 쪽이 먼저 커지는지, 그리고 PostgreSQL wait 가 어떤 형태로 동반되는지 같은 시간축에서 보는 것이었다.

### 계측 보강

- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java) 에 `post.like.add`, `post.like.remove` 직접 profile 을 추가했다.
- 문제 해결:
  - 이 조합에서는 `post.list` 와 좋아요 add/remove 가 동시에 움직인다.
  - service 내부에서 직접 `wall/sql/statement count` 를 남겨야 AOP 누락 없이 좋아요 경로의 비용을 안정적으로 수집할 수 있다.

### 유효한 실행 artifact

- fresh `loadtest` 인스턴스: `18082`
- `0.5 baseline`
  - [report](/home/admin0/effective-disco/loadtest/results/soak-20260324-232807.md)
  - [server](/home/admin0/effective-disco/loadtest/results/soak-20260324-232807-server.json)
  - [timeline](/home/admin0/effective-disco/loadtest/results/soak-20260324-232807-metrics.jsonl)
- `0.6 reproduction`
  - [report](/home/admin0/effective-disco/loadtest/results/soak-20260324-232919.md)
  - [server](/home/admin0/effective-disco/loadtest/results/soak-20260324-232919-server.json)
  - [timeline](/home/admin0/effective-disco/loadtest/results/soak-20260324-232919-metrics.jsonl)

### 비교 결과

- broad health
  - `0.5`: `p95=354.96ms`, `p99=471.57ms`, `dbPoolTimeouts=0`, `maxThreadsAwaitingConnection=91`
  - `0.6`: `p95=824.89ms`, `p99=1005.76ms`, `unexpected_response_rate=0.0070`, `dbPoolTimeouts=88`, `maxThreadsAwaitingConnection=152`
- `post.list`
  - `averageWallTimeMs = 152.46 -> 213.22`
  - `averageSqlExecutionTimeMs = 147.95 -> 208.62`
  - `maxSqlExecutionTimeMs = 511.43 -> 538.58`
  - `averageSqlStatementCount = 4.80 -> 4.81`
- `post.like.add`
  - `averageWallTimeMs = 21.17 -> 13.94`
  - `averageSqlExecutionTimeMs = 20.23 -> 12.94`
  - `maxTransactionTimeMs = 89.05 -> 97.80`
  - `averageSqlStatementCount = 4.00 -> 4.00`
- `post.like.remove`
  - `averageWallTimeMs = 21.78 -> 13.83`
  - `averageSqlExecutionTimeMs = 20.84 -> 12.82`
  - `maxTransactionTimeMs = 74.84 -> 101.78`
  - `averageSqlStatementCount = 4.00 -> 4.00`
- PostgreSQL peak
  - `waitingSessions`: `18 -> 19`
  - `lockWaitingSessions`: `16 -> 17`
  - `longestQueryMs = 217 -> 273`
  - `longestTransactionMs = 321 -> 389`
  - 두 run 모두 `Lock/tuple`, `Lock/transactionid` 가 보였고, `0.6` 에서 `IO/WALSync` 가 추가로 관측됐다.

### 해석

- 이번 정밀 계측으로 `like add/remove` 자체가 먼저 폭증하는 경로는 아니라는 점이 더 분명해졌다.
- `0.6` 에서 먼저 크게 커진 것은 `post.list` 의 SQL 시간과 pool 대기열이었다.
- 반대로 `post.like.add/remove` 의 평균 비용은 오히려 낮아졌고, max transaction time 만 `~100ms` 수준으로 유지됐다.
- 즉 좋아요 경로는 `tuple/transactionid lock + WAL pressure` 를 더하는 write 경로이지만, 현재 mixed 불안정성의 first-order driver 는 `feed/search read pressure 로 비대해진 post.list` 쪽이다.
- 따라서 다음 최적화 우선순위는 `like SQL 자체`보다 `post.list under concurrent like churn` 이다.

### 다음 액션

- `browse_board_feed + search_catalog + like_mixed` 조합에서 `post.list` query shape 를 더 줄일 수 있는지 점검
- 필요하면 search count/query 와 feed list query 를 더 분리 계측
- 같은 조합 재측정 후 `dbPoolTimeouts`, `post.list averageSqlExecutionTimeMs`, `longestQueryMs` 감소 여부 확인

## 2026-03-25 post.list 세분화 계측 + API browse/search slice 전환

상태: 구현 완료

### 배경

- 이전 단계까지의 결론은 `browse_board_feed + search_catalog + like_mixed` 에서 `post.list` 가 먼저 커진다는 것이었다.
- 하지만 `post.list` 하나만으로는 `feed rows`, `search rows`, `tag rows`, `search count(*)` 중 무엇이 먼저 비싸지는지 분리되지 않았다.
- 동시에 loadtest 의 API browse/search 경로는 여전히 `/api/posts?page=...` 를 통해 `Page` 기반으로 동작해, hot path 에서 계속 `count(*)` 를 동반하고 있었다.

### 구현

- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - `post.list.browse.rows`
  - `post.list.search.rows`
  - `post.list.tag.rows`
  로 profile 을 직접 분리했다.
- [PostController.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/controller/PostController.java)
  - 새 API `GET /api/posts/slice` 를 추가했다.
  - board browse(`latest/likes/comments`) 와 keyword/tag search 를 `cursor/slice` 로 조회한다.
- [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  - keyword search native SQL 에 `Slice` 전용 경로를 추가했다.
  - API hot path 에서는 `count(*)` 를 제거하고 rows query 만 수행한다.
- [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - board `likes/comments` browse 와 tag filter 에 대해 `Slice` 전용 query 를 추가했다.
- [PostScrollResponse.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/dto/response/PostScrollResponse.java)
  - `nextCursorSortValue` 를 추가해 좋아요순/댓글순도 같은 cursor 응답 형식으로 다룰 수 있게 했다.
- [bbs-load.js](/home/admin0/effective-disco/loadtest/k6/bbs-load.js)
  - `browse_board_feed`, `search_catalog` 시나리오가 `/api/posts/slice` 를 사용하도록 바꿨다.

### 문제 해결

- browse/search API hot path 에서 `count(*)` 를 제거했다.
- `post.list` 를 한 덩어리로 보지 않고 `browse/search/tag` rows 로 분리해 다음 측정에서 원인을 더 바로 읽을 수 있게 했다.
- web 전용 `/boards/{slug}/scroll` 경로는 그대로 두고, loadtest 와 REST hot path 만 새 slice API 로 옮겨 영향 범위를 좁혔다.

### 검증

- targeted Gradle 테스트 통과:
  - `PostControllerTest`
  - `PostListOptimizationIntegrationTest`
  - `PostServiceTest`
- `k6 archive loadtest/k6/bbs-load.js -O /tmp/bbs-load.tar` 통과
- 특히 [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java) 에서 keyword search slice 가 `count(*)` 없이 bounded statement count 로 끝나는지 검증했다.

### 다음 액션

- 새 hot path 기준으로 `browse_board_feed + search_catalog + like_mixed` short soak 재측정
- `post.list.browse.rows`, `post.list.search.rows`, `post.list.tag.rows` 중 어느 profile 이 먼저 커지는지 확인
- 그 결과에 따라 feed list query 또는 search rows query 를 다음 최적화 대상으로 확정

## 2026-03-25 like-focused short soak 재측정

상태: 완료

### 배경

- 앞 단계에서 `post.list` 세분화 계측과 `/api/posts/slice` 전환을 넣었다.
- 따라서 기존 like-focused 최소 재현 조합이 최신 hot path 기준으로도 여전히 같은 방식으로 깨지는지 다시 확인할 필요가 있었다.

### 실행 artifact

- fresh `loadtest` 인스턴스: `18082`
- `0.5 baseline`
  - [report](/home/admin0/effective-disco/loadtest/results/soak-20260325-055840.md)
  - [server](/home/admin0/effective-disco/loadtest/results/soak-20260325-055840-server.json)
- `0.6 reproduction`
  - [report](/home/admin0/effective-disco/loadtest/results/soak-20260325-055919.md)
  - [server](/home/admin0/effective-disco/loadtest/results/soak-20260325-055919-server.json)

### 비교 결과

- broad health
  - `0.5`: `p95=134.77ms`, `p99=156.79ms`, `dbPoolTimeouts=0`
  - `0.6`: `p95=169.70ms`, `p99=200.12ms`, `dbPoolTimeouts=0`
- `post.list.browse.rows`
  - `averageSqlExecutionTimeMs = 59.49 -> 62.84`
  - `averageSqlStatementCount = 1.00 -> 1.00`
- `post.list.search.rows`
  - `averageSqlExecutionTimeMs = 47.90 -> 49.59`
  - `averageSqlStatementCount = 1.00 -> 1.00`
- `post.list.tag.rows`
  - `averageSqlExecutionTimeMs = 82.90 -> 85.32`
  - `averageSqlStatementCount = 1.00 -> 1.00`
- `post.like.add/remove`
  - 둘 다 `averageSqlExecutionTimeMs ≈ 17~18ms`
  - `averageSqlStatementCount = 4.00`

### 해석

- `/api/posts/slice` 전환 후 like-focused 최소 재현 조합은 short soak 기준으로 더 이상 깨지지 않았다.
- 이 시점의 병목 우선순위는 `like add/remove` 가 아니라 read path 이고, 세분화 profile 로 보면 `search rows`보다 `tag rows` 가 더 비싸다.
- 특히 browse/search/tag rows 는 모두 `averageSqlStatementCount = 1.00` 으로 내려갔기 때문에, 이전 `Page + count(*)` hot path 와는 병목 성격이 달라졌다.

## 2026-03-25 like-focused 반복 안정성 재측정

상태: 완료

### 실행 artifact

- [suite](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260325-060233/sub-stability-20260325-060233.md)
- [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260325-060233/sub-stability-20260325-060233-aggregate.tsv)

조건:

- fresh `loadtest` 인스턴스 `18082`
- `RUNS=5`
- `STAGE_FACTORS=0.6,0.7`
- `SCENARIO_PROFILE=browse_board_feed+search_catalog+like_mixed`

### 결과

- `0.6`: `5/5 PASS`
  - max `p99 = 341.31ms`
  - max `dbPoolTimeouts = 0`
  - max `waiting = 95`
- `0.7`: `3/5 PASS`, `2/5 FAIL`
  - max `p99 = 822.23ms`
  - max `dbPoolTimeouts = 6`
  - max `waiting = 156`
- `highest stable factor = 0.6`

### 해석

- 최신 hot path 기준으로 like-focused 최소 재현 조합의 current baseline 은 `0.6 안정 / 0.7 불안정` 이다.
- 즉 예전처럼 `0.55/0.6` 에서 바로 무너지던 상태는 넘겼고, `slice API + post.list 세분화` 가 실제로 기준선을 끌어올린 것으로 봐도 된다.

### 다음 액션

- broad mixed 를 다시 측정해 like-focused 개선이 전체 mixed 에도 전파되는지 확인
- `tag rows` 가 실제로 다음 병목인지 검증
- 필요하면 tag filter query 를 다음 최적화 대상으로 전환

## 2026-03-25 broad mixed 반복 안정성 재측정

상태: 완료

### 배경

- `like-focused` 최소 재현 조합은 최신 hot path 기준으로 `0.6 안정 / 0.7 불안정` 까지 회복됐다.
- 하지만 이 개선이 broad mixed 전체로 전파됐는지는 별도 확인이 필요했다.

### 실행 artifact

- [suite](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-061955.md)
- [tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-061955.tsv)
- [aggregate](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-061955-aggregate.tsv)

조건:

- fresh `loadtest` 인스턴스 `18082`
- `RUNS=5`
- `STAGE_FACTORS=0.6,0.7`
- 전체 broad mixed 시나리오

### 결과

- `0.6 = 1 PASS / 1 LIMIT / 3 FAIL`
  - max `http p99 = 1391.67ms`
  - max `dbPoolTimeouts = 623`
  - max `waiting = 192`
- `0.7 = 0 PASS / 1 LIMIT / 0 FAIL`
  - `http p99 = 777.62ms`
  - `dbPoolTimeouts = 2`
  - `waiting = 187`
- `highest stable factor = n/a`
- 모든 broad mixed 실패 런에서도
  - `duplicateKeyConflicts = 0`
  - 관계 중복 row = `0`
  - `postLike/comment/unread mismatch = 0`

### 해석

- `like-focused` 조합 개선은 실제였지만, broad mixed 전체 안정화로 바로 이어지지는 않았다.
- 따라서 남은 병목은 `like` 단일 조합 자체가 아니라, 최신 코드 기준에서도 살아 있는 `cross-profile interaction` 이다.
- 이 결과는 broad mixed 전체를 계속 반복하기보다, 최신 코드 기준으로 최소 재현 조합을 다시 좁혀야 한다는 근거가 됐다.

## 2026-03-25 최신 코드 기준 scenario matrix 재실행

상태: 완료

### 배경

- broad mixed 가 여전히 불안정했기 때문에, 최신 코드 기준으로도 단일 profile 들이 각각 안정적인지 다시 확인해야 했다.
- 목적은 `어떤 단일 profile 이 문제인가`를 찾는 것이 아니라, `단일 profile 은 괜찮고 조합에서만 깨지는가`를 최신 코드 기준으로 재검증하는 것이었다.

### 실행 artifact

- [summary](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260325-062843.md)
- [summary tsv](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260325-062843.tsv)

조건:

- fresh `loadtest` 인스턴스 `18081`
- `RUNS=5`
- `STAGE_FACTORS=0.5,0.55,0.6`
- profiles:
  - `browse_search`
  - `write`
  - `relation_mixed`
  - `notification`

### 결과

- `browse_search`: `0.5/0.55/0.6` 모두 `5/5 PASS`
- `write`: `0.5/0.55/0.6` 모두 `5/5 PASS`
- `relation_mixed`: `0.5/0.55/0.6` 모두 `5/5 PASS`
- `notification`: `0.5/0.55/0.6` 모두 `5/5 PASS`
- 각 profile 의 `highest stable factor = 0.6`

### 해석

- 최신 코드 기준으로는 단일 profile 이 broad mixed 불안정성의 직접 원인이 아니다.
- 즉 현재 남은 문제는 `read`, `write`, `relation`, `notification` 중 하나가 혼자 깨지는 구조가 아니라, 여러 profile 이 동시에 DB pool 과 트랜잭션 점유 시간을 밀어 올리는 조합 문제다.
- 다음 단계는 broad mixed 자체를 계속 보는 것이 아니라, 최신 코드 기준 `2-profile / 3-profile` 조합을 다시 좁혀 최소 재현 조건을 재확정하는 것이다.

## 2026-03-25 조합 matrix 러너 추가 + 최소 재현 크기 재확정

상태: 완료

### 배경

- 최신 broad mixed 는 여전히 불안정했지만, 단일 profile 은 모두 `0.6` 까지 안정적이었다.
- 따라서 다음 질문은 "`최소 재현 조건`의 크기가 지금도 `2-profile` 인가, 아니면 `3-profile` 까지 가야 하는가" 였다.
- 이 질문은 broad mixed 전체를 반복하는 것보다, `0.6` 에서 pair/triple 조합을 체계적으로 재는 편이 더 직접적이다.

### 구현

- [run-bbs-scenario-combination-matrix.sh](/home/admin0/effective-disco/loadtest/run-bbs-scenario-combination-matrix.sh)
  - `BASE_PROFILES` 에서 `2-profile / 3-profile` 조합을 자동 생성한다.
  - 각 조합을 [run-bbs-scenario-sub-stability.sh](/home/admin0/effective-disco/loadtest/run-bbs-scenario-sub-stability.sh) 로 반복 측정한다.
  - `highest stable factor`, `unstable` 여부, aggregate 경로를 하나의 summary 로 모은다.
  - 기본값 `STOP_AFTER_FIRST_UNSTABLE_SIZE=1` 로, pair 에서 이미 실패 조합이 나오면 triple 로 불필요하게 올라가지 않는다.

### 문제 해결

- 최신 코드 기준으로 broad mixed 최소 재현 조건의 `크기` 를 수작업이 아니라 자동으로 다시 확정할 수 있게 했다.
- broad mixed 가 실제로 깨지는 factor 하나만 집중 측정할 수 있어, full grid 로 시간을 과하게 쓰지 않게 했다.
- unstable size 가 확인되면 더 큰 조합을 자동으로 생략해, 원인 분리 속도를 높였다.

### 실행 artifact

- [pair summary](/home/admin0/effective-disco/loadtest/results/scenario-combination-matrix-20260325-075043.md)

조건:

- fresh `loadtest` 인스턴스 `18081`
- `RUNS=5`
- `STAGE_FACTORS=0.6`
- `COMBINATION_SIZES=2`
- pair 에서 unstable 조합이 확인되면 종료

### 결과

- `browse_search+write`: `0.6 = 5/5 PASS`
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+write-20260325-075043/scenario-browse_search+write-20260325-075043/sub-stability-20260325-075043-aggregate.tsv)
- `browse_search+relation_mixed`: `0.6 = 0/5 PASS, 5/5 FAIL`
  - [suite](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-075043/scenario-browse_search+relation_mixed-20260325-075555/sub-stability-20260325-075555.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-075043/scenario-browse_search+relation_mixed-20260325-075555/sub-stability-20260325-075555-aggregate.tsv)
  - max `http p99 = 962.75ms`
  - max `dbPoolTimeouts = 95`
  - max `waiting = 173`
- `browse_search+notification`: `0.6 = 5/5 PASS`
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+notification-20260325-075043/scenario-browse_search+notification-20260325-075955/sub-stability-20260325-075955-aggregate.tsv)

### 해석

- 최신 코드 기준 최소 재현 크기는 다시 `2-profile` 로 확정됐다.
- 그리고 현재 가장 강한 pair 재현 조합은 다시 `browse_search + relation_mixed` 이다.
- 즉 `like-focused` 개선과 API slice 전환 이후에도, broad mixed 의 남은 핵심은 여전히 `read pressure + relation write pressure` 의 상호작용이다.

### 다음 액션

- `browse_search + relation_mixed` 를 다시 세분화해 `browse_board_feed / search_catalog / tag path` 와 `like/follow/bookmark/block` 중 어떤 하위 조합이 현재 최소 재현 조합인지 최신 기준으로 좁히기
- 그 조합 하나에만 PostgreSQL wait / slow query 계측을 집중하기

## 2026-03-25 search split + read × relation pair matrix

상태: 완료

### 배경

- `browse_search + relation_mixed` 는 최신 코드에서도 여전히 실패했지만, 이 수준으로는 read pressure 가 너무 크다.
- 최근 계측에서는 `post.list.tag.rows` 가 상대적으로 비쌌기 때문에, `search_catalog` 안의 tag query 를 따로 떼지 않으면 최소 재현 pair 를 다시 놓칠 수 있었다.

### 구현

- [bbs-load.js](/home/admin0/effective-disco/loadtest/k6/bbs-load.js)
  - `search_catalog` 를 keyword search 전용으로 축소했다.
  - `tag_search` 와 `sort_catalog` scenario/profile 을 새로 추가했다.
  - `tag_search_duration`, `sort_catalog_duration` metric 도 같이 추가했다.
- [run-bbs-scenario-sub-stability.sh](/home/admin0/effective-disco/loadtest/run-bbs-scenario-sub-stability.sh)
  - `tag_search`, `sort_catalog` profile 을 지원하도록 확장했다.
- [run-bbs-read-relation-pair-matrix.sh](/home/admin0/effective-disco/loadtest/run-bbs-read-relation-pair-matrix.sh)
  - `read_profile × relation_profile` 카테시안 곱을 자동으로 반복 측정하는 전용 러너를 추가했다.

### 문제 해결

- `search_catalog` 하나에 keyword/tag/sort 가 섞여 있던 상태를 atomic read path 로 다시 분리했다.
- broad mixed 실패를 곧바로 큰 조합으로 보지 않고, `search/tag/sort` 와 `like/bookmark/follow/block` 의 atomic pair 가 실제로 깨지는지 먼저 확인할 수 있게 했다.
- 이 결과가 안정적이면, 다음 단계가 `pair 최적화`가 아니라 `3-way read pressure 조합` 추적으로 바로 좁혀진다.

### 실행 artifact

- [pair summary](/home/admin0/effective-disco/loadtest/results/read-relation-pair-matrix-20260325-083639.md)

조건:

- fresh `loadtest` 인스턴스 `18081`
- `RUNS=3`
- `STAGE_FACTORS=0.6`
- `READ_PROFILES=search_catalog,tag_search,sort_catalog`
- `RELATION_PROFILES=like_mixed,bookmark_mixed,follow_mixed,block_mixed`

### 결과

- `search_catalog` 의 atomic pair 는 전부 `0.6 = 3/3 PASS`
  - `search_catalog+like_mixed`
  - `search_catalog+bookmark_mixed`
  - `search_catalog+follow_mixed`
  - `search_catalog+block_mixed`
- `tag_search` 의 atomic pair 도 전부 `0.6 = 3/3 PASS`
  - `tag_search+like_mixed`
  - `tag_search+bookmark_mixed`
  - `tag_search+follow_mixed`
  - `tag_search+block_mixed`
- 대표 artifact:
  - [search_catalog+like_mixed](/home/admin0/effective-disco/loadtest/results/read-relation-search_catalog+like_mixed-20260325-083639/scenario-search_catalog+like_mixed-20260325-083639/sub-stability-20260325-083639.md)
  - [tag_search+like_mixed](/home/admin0/effective-disco/loadtest/results/read-relation-tag_search+like_mixed-20260325-083639/scenario-tag_search+like_mixed-20260325-084608/sub-stability-20260325-084608.md)
  - [tag_search+block_mixed](/home/admin0/effective-disco/loadtest/results/read-relation-tag_search+block_mixed-20260325-083639/scenario-tag_search+block_mixed-20260325-085314/sub-stability-20260325-085314.md)

### 해석

- 최신 코드 기준으로 `atomic search/tag read + atomic relation write` pair 만으로는 `0.6` broad mixed 실패를 재현하지 못했다.
- 즉 `tag_search` 가 비싸다는 사실과 `tag_search` 가 단독으로 최소 재현 pair 라는 결론은 다르다. 이번 측정으로 후자는 부정됐다.
- 따라서 남은 최소 재현 후보는 `browse_board_feed + (search_catalog or tag_search or sort_catalog) + relation_write` 수준의 `3-way read pressure` 조합이다.
- 다음 단계는 atomic pair 최적화가 아니라, `browse` 를 다시 얹은 3-way 조합으로 넘어가야 한다.
- 같은 맥락에서 `sort_catalog` 의 pair 전체는 이번 턴에서 끝까지 돌리지 않았다. `search_catalog` 와 `tag_search` 가 모두 안정적이라는 사실만으로도
  "atomic pair 가 아니라 browse 를 포함한 3-way 조합으로 넘어가야 한다"는 결론이 충분했기 때문이다.

### 다음 액션

- `browse_board_feed + search_catalog + like_mixed`
- `browse_board_feed + tag_search + like_mixed`
- `browse_board_feed + sort_catalog + like_mixed`
- 그리고 같은 패턴으로 `bookmark/follow/block` 까지 비교해, 현재 코드 기준 최소 3-way 재현 조합을 확정

## 2026-03-25 loadtest 데이터 cleanup 자동화

상태: 완료

### 배경

- local PostgreSQL `effectivedisco` 데이터베이스를 개발 앱과 `loadtest` 프로필이 함께 사용하고 있었다.
- k6 `setup()` 과 `write_posts_and_comments` 는 실제 회원/게시물/댓글을 생성한다.
- 그런데 기존 runner 들은 `/internal/load-test/reset` 으로 metrics 만 초기화하고, 실행이 만든 row 는 정리하지 않았다.
- 그래서 k6 실행 후 `./gradlew bootRun` 으로 기본 앱을 띄워 게시물 개수를 보면, 부하 테스트를 돌릴수록 게시물 수가 계속 증가하는 현상이 생겼다.

### 문제 해결

- 부하 테스트 결과 파일은 남기되, 같은 `LOADTEST_PREFIX` 범위의 실제 row 는 측정 직후 회수해야 한다.
- 그렇지 않으면:
  - 다음 부하 테스트의 데이터 분포와 기준선이 이전 실행 row 에 오염되고
  - 브라우저로 수동 확인할 때도 게시물 수가 계속 증가해 원인 분석이 흐려진다.
- 특히 이 프로젝트는 loadtest 전용 DB 를 아직 분리하지 않았기 때문에, prefix cleanup 은 최소한의 위생 장치다.

### 구현

- [LoadTestDataCleanupService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestDataCleanupService.java)
  - `username startsWith(prefix)` 사용자 집합을 기준으로 loadtest 데이터 범위를 찾는다.
  - password reset token, notification, message, post_like, report, block 을 먼저 지우고, 마지막에 `User` 를 삭제해 cascade 로 post/comment 를 회수한다.
- [LoadTestMetricsController.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestMetricsController.java)
  - `/internal/load-test/cleanup` endpoint 를 추가했다.
  - 응답에는 `matchedUsers`, `matchedPosts`, `matchedComments`, `matchedNotifications`, `matchedMessages`, `matchedPasswordResetTokens` 를 포함해 실제 cleanup 범위를 확인할 수 있게 했다.
- 저장소 count/query 보강:
  - [UserRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/UserRepository.java)
  - [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - [CommentRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/CommentRepository.java)
  - [NotificationRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/NotificationRepository.java)
  - [MessageRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/MessageRepository.java)
  - [PasswordResetTokenRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PasswordResetTokenRepository.java)
- 공식 runner 자동 cleanup:
  - [run-bbs-load.sh](/home/admin0/effective-disco/loadtest/run-bbs-load.sh)
  - [run-bbs-soak.sh](/home/admin0/effective-disco/loadtest/run-bbs-soak.sh)
  - [run-bbs-ramp-up.sh](/home/admin0/effective-disco/loadtest/run-bbs-ramp-up.sh)
  - [run-bbs-consistency-stress.sh](/home/admin0/effective-disco/loadtest/run-bbs-consistency-stress.sh)
  - metrics / SQL snapshot 저장 뒤 같은 prefix 를 `/internal/load-test/cleanup` 으로 자동 회수한다.
- 수동 cleanup 도구:
  - [cleanup-loadtest-data.sh](/home/admin0/effective-disco/loadtest/cleanup-loadtest-data.sh)
  - 과거에 남아 있던 prefix 들도 안전하게 일괄 정리할 수 있다.

### 검증

테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.loadtest.LoadTestDataCleanupServiceTest" \
  --tests "com.effectivedisco.loadtest.LoadTestMetricsControllerTest"
```

결과:

- [LoadTestDataCleanupServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/loadtest/LoadTestDataCleanupServiceTest.java)
  - prefix 사용자 2명, 게시물/댓글/좋아요/알림/메시지/token 을 만든 뒤 cleanup 시 실제 row 가 함께 제거되는지 검증
- [LoadTestMetricsControllerTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/loadtest/LoadTestMetricsControllerTest.java)
  - `/internal/load-test/cleanup` endpoint 응답 스키마 검증
- shell 문법 검증:
  - `bash -n loadtest/run-bbs-load.sh`
  - `bash -n loadtest/run-bbs-soak.sh`
  - `bash -n loadtest/run-bbs-ramp-up.sh`
  - `bash -n loadtest/run-bbs-consistency-stress.sh`
  - `bash -n loadtest/cleanup-loadtest-data.sh`

### 해석

- 이번 변경은 p95/p99 를 직접 낮춘 최적화는 아니다.
- 대신 "측정이 끝난 뒤 DB 가 원래 상태로 돌아오지 않는다"는 실험 위생 문제를 해결했다.
- 앞으로 공식 runner 결과는 `LOADTEST_PREFIX` 기준 cleanup 이 자동 수행된 상태를 전제로 해석하는 것이 맞다.
- 반대로 이 변경 전의 직접 `k6 run` 실행이나 cleanup 이전 잔존 데이터는, 게시물 수 증가와 데이터 분포 변형을 유발할 수 있으므로 해석 시 주의가 필요하다.

### 다음 액션

- 기존에 남아 있는 과거 loadtest prefix 가 있다면 [cleanup-loadtest-data.sh](/home/admin0/effective-disco/loadtest/cleanup-loadtest-data.sh) 로 수동 정리
- 가능하면 장기적으로는 개발 앱과 분리된 loadtest 전용 DB 또는 schema 로 실행 환경을 완전히 분리

## 2026-03-25 loadtest 전용 DB 분리

상태: 완료

### 배경

- prefix cleanup 을 넣어도, loadtest 프로필이 기본 개발 DB `effectivedisco` 를 바라보는 구조 자체는 여전히 위험했다.
- cleanup 이전에 이미 누적된 row 가 남아 있을 수 있고, 직접 `k6 run` 또는 cleanup 실패 시 다시 같은 문제가 반복될 수 있다.
- 따라서 "끝나고 지운다"보다 "처음부터 다른 DB를 쓴다"가 더 강한 해법이다.

### 문제 해결

- loadtest 프로필의 기본 datasource 를 개발 DB와 물리적으로 분리된 전용 DB로 바꾼다.
- 동시에, 실수로 loadtest 프로필이 다시 개발 DB를 가리키면 기동 단계에서 즉시 실패하게 막는다.
- 이렇게 해야 향후 baseline 재측정은 "공유 DB 오염"이 아닌 "현재 코드와 현재 시나리오"만 반영한 결과로 해석할 수 있다.

### 구현

- [application-loadtest.yml](/home/admin0/effective-disco/src/main/resources/application-loadtest.yml)
  - `spring.datasource.url` 기본값을 `jdbc:postgresql://localhost:5432/effectivedisco_loadtest` 로 변경
  - `APP_LOAD_TEST_DB_NAME`, `APP_LOAD_TEST_DB_USERNAME`, `APP_LOAD_TEST_DB_PASSWORD`, `APP_LOAD_TEST_DB_DRIVER_CLASS_NAME` 환경 변수 지원 추가
  - `app.load-test.datasource.enforce-isolation=true`
  - `app.load-test.datasource.required-db-name=effectivedisco_loadtest`
- [LoadTestDatasourceIsolationGuard.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestDatasourceIsolationGuard.java)
  - `loadtest` 프로필에서 실제 JDBC URL 의 DB 이름을 파싱
  - 지정된 전용 DB 이름이 아니면 `IllegalStateException` 으로 기동 차단
- [create-loadtest-db.sh](/home/admin0/effective-disco/loadtest/create-loadtest-db.sh)
  - `effectivedisco_loadtest` DB 가 없으면 생성하는 보조 스크립트 추가
- [loadtest/README.md](/home/admin0/effective-disco/loadtest/README.md)
  - 전용 DB 생성, 환경 변수 override, 격리 가드 동작 방식 문서화

### 검증

테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.loadtest.LoadTestDatasourceIsolationGuardTest"
```

결과:

- [LoadTestDatasourceIsolationGuardTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/loadtest/LoadTestDatasourceIsolationGuardTest.java)
  - JDBC URL 에서 DB 이름 파싱 확인
  - `effectivedisco_loadtest` 는 허용
  - `effectivedisco` 는 거부
- shell 문법 검증:
  - `bash -n loadtest/create-loadtest-db.sh`

### 해석

- 이번 변경은 성능 최적화가 아니라 측정 기준선을 보호하는 환경 격리 조치다.
- cleanup 도입 이전의 성능 수치와 안정 factor 는 방향성 참고용으로만 보는 것이 맞다.
- 앞으로 새 baseline 은 "전용 loadtest DB + 공식 runner + 자동 cleanup" 조합에서 다시 만들어야 한다.

### 다음 액션

- `./loadtest/create-loadtest-db.sh`
- `SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun`
- 깨끗한 전용 DB 에서 single-profile matrix, pair matrix, broad mixed, soak baseline 을 순서대로 재측정

## 2026-03-25 clean 전용 DB baseline 재측정

상태: 완료

### 배경

- loadtest 전용 DB 를 분리한 뒤에도, 과거 shared DB 기준선과 얼마나 달라지는지 실제로 다시 재지 않으면 문서의 `stable factor` 와 `p95/p99` 를 현재 값으로 간주할 수 없었다.
- 특히 과거에는 `browse_search + relation_mixed`, broad mixed 모두 불안정 신호가 반복적으로 관측됐기 때문에, 전용 DB 에서 그 현상이 다시 재현되는지가 핵심이었다.

### 문제 해결

- `effectivedisco_loadtest` DB 를 `DROP/CREATE` 로 매번 비운다.
- 그 위에 `loadtest` 앱 하나만 띄워 단일 인스턴스 / 단일 DB / 자동 cleanup 조건으로 clean baseline 을 다시 만든다.
- 이전 기준선과 직접 비교하기 위해, 과거에 자주 쓰던 시나리오를 우선 재실행한다.
  - `browse_search`
  - broad mixed `0.6 / 0.65 / 0.7`
  - `browse_search+write`
  - `browse_search+relation_mixed`

### 실행

대표 실행 순서:

```bash
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'DROP DATABASE IF EXISTS "effectivedisco_loadtest" WITH (FORCE);'
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'CREATE DATABASE "effectivedisco_loadtest";'

APP_LOAD_TEST_DB_POOL_MAX_SIZE=28 \
SPRING_PROFILES_ACTIVE=loadtest \
SERVER_PORT=18084 \
GRADLE_USER_HOME=/tmp/gradle-home \
./gradlew bootRun --no-daemon
```

그 위에서 순차 실행:

```bash
BASE_URL=http://127.0.0.1:18084 RUNS=5 STAGE_FACTORS=0.5,0.55,0.6 \
  ./loadtest/run-bbs-scenario-matrix.sh

RUNS=5 STAGE_FACTORS=0.6,0.65,0.7 BASE_URL=http://127.0.0.1:18084 \
  STOP_ON_HTTP_P99_MS=800 STOP_ON_K6_THRESHOLD=0 \
  ./loadtest/run-bbs-sub-stability.sh

BASE_URL=http://127.0.0.1:18084 RUNS=5 STAGE_FACTORS=0.6 COMBINATION_SIZES=2 \
  ./loadtest/run-bbs-scenario-combination-matrix.sh
```

### 결과

- clean `browse_search`
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search-20260325-095737/scenario-browse_search-20260325-095737/sub-stability-20260325-095737-aggregate.tsv)
  - `0.5 / 0.55 / 0.6 = 5/5 PASS`
  - max `p99 = 9.77ms / 7.04ms / 7.31ms`
- clean broad mixed
  - [suite](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-110827.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-110827-aggregate.tsv)
  - `0.6 / 0.65 / 0.7 = 5/5 PASS`
  - max `p99 = 209.81ms / 230.70ms / 244.76ms`
  - `dbPoolTimeouts = 0`
- clean pair
  - `browse_search+write`
    - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+write-20260325-112422/scenario-browse_search+write-20260325-112422/sub-stability-20260325-112422-aggregate.tsv)
    - `0.6 = 5/5 PASS`
  - `browse_search+relation_mixed`
    - [suite](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-112422/scenario-browse_search+relation_mixed-20260325-112937/sub-stability-20260325-112937.md)
    - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-112422/scenario-browse_search+relation_mixed-20260325-112937/sub-stability-20260325-112937-aggregate.tsv)
    - `0.6 = 5/5 PASS`
    - max `p99 = 177.88ms`, `dbPoolTimeouts = 0`

### 해석

- 가장 중요한 결론은, shared DB 기준으로 반복 관측되던 `broad mixed unstable` 과 `browse_search+relation_mixed 재현` 이 clean 전용 DB 기준으로는 더 이상 재현되지 않았다는 점이다.
- 즉 이전의 `stable factor = n/a`, `browse_search+relation_mixed = 0.6 FAIL` 같은 결론은 현재 코드의 본질적 한계라기보다 shared DB 오염과 데이터 누적의 영향을 크게 받았다고 보는 것이 맞다.
- clean 기준선에서는 현재 적어도 `broad mixed 0.7` 까지는 안정적이다.
- 따라서 문서 해석 기준도 바뀐다.
  - cleanup/전용 DB 분리 이전 결과: 방향성 참고용
  - cleanup/전용 DB 분리 이후 결과: 현재 baseline

### 다음 액션

- clean baseline 기준으로 `0.8 / 0.85 / 0.9` broad mixed 재측정
- `0.7 / 30분` soak 실행
- pair matrix 나머지 조합은 broad mixed 가 다시 흔들릴 때만 보강

## 2026-03-25 clean baseline 상향 재측정 + `0.9 / 30분` soak

상태: 완료

### 배경

- clean 전용 DB 기준 첫 baseline 에서 broad mixed `0.7` 까지 안정적이라는 사실은 확인됐다.
- 따라서 다음 질문은 두 가지였다.
  - 안정 구간이 실제로 어디까지 올라가느냐
  - 그 안정 구간이 `30분` 장시간 혼합 부하에서도 유지되느냐

### 문제 해결

- 같은 방식으로 전용 `effectivedisco_loadtest` DB 를 다시 비우고, broad mixed를 더 높은 factor 에서 반복 측정했다.
- 그 결과를 기준으로 가장 높은 clean stable factor 로 `30분 soak`를 바로 검증했다.

### 실행

반복 측정:

```bash
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'DROP DATABASE IF EXISTS "effectivedisco_loadtest" WITH (FORCE);'
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'CREATE DATABASE "effectivedisco_loadtest";'

APP_LOAD_TEST_DB_POOL_MAX_SIZE=28 \
SPRING_PROFILES_ACTIVE=loadtest \
SERVER_PORT=18084 \
GRADLE_USER_HOME=/tmp/gradle-home \
./gradlew bootRun --no-daemon

RUNS=5 STAGE_FACTORS=0.8,0.85,0.9 BASE_URL=http://127.0.0.1:18084 \
  STOP_ON_HTTP_P99_MS=800 STOP_ON_K6_THRESHOLD=0 \
  ./loadtest/run-bbs-sub-stability.sh
```

장시간 soak:

```bash
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'DROP DATABASE IF EXISTS "effectivedisco_loadtest" WITH (FORCE);'
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'CREATE DATABASE "effectivedisco_loadtest";'

APP_LOAD_TEST_DB_POOL_MAX_SIZE=28 \
SPRING_PROFILES_ACTIVE=loadtest \
SERVER_PORT=18084 \
GRADLE_USER_HOME=/tmp/gradle-home \
./gradlew bootRun --no-daemon

SOAK_FACTOR=0.9 SOAK_DURATION=30m WARMUP_DURATION=2m SAMPLE_INTERVAL_SECONDS=30 \
  BASE_URL=http://127.0.0.1:18084 \
  ./loadtest/run-bbs-soak.sh
```

### 결과

- clean broad mixed 재측정:
  - [suite](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-121551.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-121551-aggregate.tsv)
  - `0.8 / 0.85 / 0.9 = 5/5 PASS`
  - max `p99 = 262.01ms / 276.54ms / 277.59ms`
  - `dbPoolTimeouts = 0`
  - clean `highest stable factor = 0.9`
- clean `0.9 / 30분 soak`:
  - [suite](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923.md)
  - [server metrics](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923-server.json)
  - [metrics timeline](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923-metrics.jsonl)
  - [sql snapshot](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923-sql.tsv)
  - `PASS`
  - `http p95 = 293.97ms`
  - `http p99 = 377.47ms`
  - `unexpected_response_rate = 0.0000`
  - `dbPoolTimeouts = 0`
  - SQL mismatch = 전부 `0`

### 해석

- 이제 clean 전용 DB 기준으로는 broad mixed 안정 구간이 `0.9` 까지 올라갔다.
- 더 중요한 점은 `0.9 / 30분 soak` 도 통과했다는 것이다. 즉 이전 shared DB 기준선에서 보였던 불안정성은 현재 baseline 으로는 재현되지 않는다.
- 장시간 profile 을 보면 `notification.read-all.summary`, `notification.store`, `post.list.browse.rows` 가 상대적으로 커지긴 한다.
  - 그래도 이번 30분 구간에서는 `dbPoolTimeouts=0`, `unexpected_response_rate=0`, SQL mismatch `0` 를 유지했다.
- 따라서 현재 실무 해석은 이렇다.
  - 정합성과 pool 안정성은 clean baseline 기준으로 충분히 확인됐다.
  - 다음 관심사는 “깨지는가”보다 “장시간에 어떤 경로가 가장 먼저 커지는가” 쪽이다.

### 다음 액션

- `0.9 / 1시간`, `0.9 / 2시간` soak
- 장기 trend 기준 `notification.read-all.summary`, `notification.store`, `post.list.browse.rows` 추가 분석
- 필요할 때만 pair matrix / minimal reproduction 재실행

## 2026-03-25 clean `0.9 / 1시간` soak + 장시간 drift 분석

상태: 완료

### 배경

- clean 전용 DB 기준 `0.9 / 30분 soak` 는 이미 통과했다.
- 따라서 다음 질문은 `1시간`으로 늘렸을 때도 정합성과 pool 안정성이 유지되는지, 그리고 어떤 경로가 가장 먼저 drift 하는지였다.

### 문제 해결

- 측정 목적은 “깨지는가” 하나가 아니라, `1시간` 동안 어떤 hot path 의 wall/sql 시간이 누적되는지 보는 것이었다.
- 그래서 기존과 같은 clean 전용 DB 재생성 절차를 유지한 채 `0.9 / 1시간 soak` 를 실행하고,
  최종 server profile 에서 `notification.read-all.summary`, `notification.store`, `post.list.*`, `post.like.*` 를 따로 비교했다.

### 실행

```bash
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'DROP DATABASE IF EXISTS "effectivedisco_loadtest" WITH (FORCE);'
PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=4321 \
  psql -d postgres -c 'CREATE DATABASE "effectivedisco_loadtest";'

APP_LOAD_TEST_DB_POOL_MAX_SIZE=28 \
SPRING_PROFILES_ACTIVE=loadtest \
SERVER_PORT=18084 \
GRADLE_USER_HOME=/tmp/gradle-home \
./gradlew bootRun --no-daemon

SOAK_FACTOR=0.9 SOAK_DURATION=1h WARMUP_DURATION=2m SAMPLE_INTERVAL_SECONDS=60 \
  BASE_URL=http://127.0.0.1:18084 \
  ./loadtest/run-bbs-soak.sh
```

### 결과

- suite:
  [soak-20260325-141313.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313.md)
- server metrics:
  [soak-20260325-141313-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313-server.json)
- metrics timeline:
  [soak-20260325-141313-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313-metrics.jsonl)
- sql snapshot:
  [soak-20260325-141313-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313-sql.tsv)
- 최종 상태: `FAIL`
- `http p95 = 441.09ms`
- `http p99 = 570.73ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- SQL mismatch = 전부 `0`

주요 profile:

- `notification.read-all.summary avgWall = 47.41ms`, `avgSql = 46.99ms`, `avgStmt = 2.0`
- `notification.store avgWall = 22.17ms`, `avgSql = 21.78ms`, `avgStmt = 2.0`
- `post.list.browse.rows avgWall = 15.52ms`, `avgSql = 15.12ms`, `avgStmt = 1.0`
- `post.list.search.rows avgWall = 10.93ms`, `avgSql = 10.42ms`, `avgStmt = 1.0`
- `post.list.tag.rows avgWall = 1.86ms`, `avgSql = 1.46ms`, `avgStmt = 1.0`
- `post.like.add avgWall = 5.68ms`, `avgSql = 4.98ms`, `avgStmt = 4.0`
- `post.like.remove avgWall = 5.57ms`, `avgSql = 4.87ms`, `avgStmt = 4.0`

### 해석

- 이번 `FAIL` 은 `dbPoolTimeouts=0`, `unexpected_response_rate=0`, SQL mismatch `0` 상태에서 발생했다.
- 즉 clean baseline 기준 현재 시스템은 `1시간` 동안 정합성과 pool 안정성은 유지하지만,
  일부 hot path latency 가 k6 threshold 를 넘기면서 FAIL 로 분류된다.
- 드러난 드리프트는 크게 두 축이다.
  - `notification.read-all.summary`, `notification.store`
    - 둘 다 평균 wall time 이 거의 SQL 시간과 같아서 애플리케이션 계산보다 DB 비용 문제다.
    - 현재 구현은 [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java) 에서
      `findByUsernameForUpdate()` 기반 사용자 행 잠금과 unread 상태 전환을 같이 수행하므로,
      장시간 혼합 부하에서 lock/WAL pressure 가 누적되기 쉽다.
  - `post.list.browse.rows`
    - 이미 slice/keyset 으로 줄였지만, browse hot path 는 여전히 `Post + author + board projection` 을 반복한다.
    - 장시간 동안 feed browse 와 지속적인 write churn 이 겹치면서 최신 board tail read 비용이 꾸준히 상승한다.

### 다음 액션

- `GET /notifications` 자동 read-all 제거 또는 read-all 을 명시 액션으로 분리
- `notification.store/read-all` 의 `User` 전체 행 잠금 경로를 더 좁은 원자 update 로 축소
- `post.list.browse.rows` 를 `id-first keyset + small projection join` 구조로 더 줄이기
- 위 세 경로 조정 후 `0.9 / 1시간` soak 재측정

## 2026-03-25 notification/browse drift 완화 구현

상태: 구현 완료, 재측정 전

### 배경

- clean `0.9 / 1시간 soak` 에서 정합성과 pool timeout 은 안정적이었지만, latency drift 는 남았다.
- 최종 profile 기준 가장 많이 커진 경로는 아래 세 개였다.
  - `notification.read-all.summary`
  - `notification.store`
  - `post.list.browse.rows`
- 따라서 이번 단계의 목적은 `정합성 보장`은 유지한 채, 알림 경로의 사용자 row 직렬화와 browse latest hot read 폭을 먼저 줄이는 것이었다.

### 문제 해결

- 알림 경로
  - 기존 구현은 [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java) 에서
    `findByUsernameForUpdate()` 로 `User` 전체 행을 잠그고, 같은 트랜잭션에서 unread counter 와 read-all 상태 전환을 처리했다.
  - 이 구조는 `notification.store` 와 `notification.read-all.summary` 가 같은 수신자에 대해 강하게 직렬화되면서
    장시간 soak 에서 `tuple / transactionid / WAL` pressure 를 키웠다.
  - 이번 변경은 다음 방식으로 줄였다.
    - [UserRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/UserRepository.java)
      - `NotificationRecipientSnapshot`
      - `incrementUnreadNotificationCount`
      - `decrementUnreadNotificationCount`
      - `setUnreadNotificationCount`
    - [NotificationRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/NotificationRepository.java)
      - `findLatestNotificationIdByRecipientId`
      - `markAllAsReadUpToId`
      - `countUnreadByRecipientId`
    - [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java)
      - `recipient snapshot + 원자 unread counter update`
      - `cutoff id 기반 read-all`
      - `after-commit unread count refresh`
  - 핵심은 `User FOR UPDATE` 를 hot path 에서 제거하고, read-all 은 현재 시점 이전 알림까지만 읽음 처리하게 바꾼 것이다.

- browse latest 경로
  - 기존 `post.list.browse.rows` 는 keyset pagination 을 쓰고 있었지만, 정렬과 `author/board` join projection 을 같은 SQL 에서 처리했다.
  - 장시간 soak 에서는 이 browse latest SQL 이 계속 최신 tail 을 훑으면서 누적 비용이 커졌다.
  - 이번 변경은 다음 방식으로 줄였다.
    - [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
      - `findScrollPostIdsByBoardOrderByCreatedAtDesc`
      - `findScrollPostIdsByBoardAndCreatedAtBefore`
      - `findPostListRowsByIdIn`
    - [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
      - `loadLatestBrowseSlice`
  - 최신순 browse 는 이제
    - 1단계: `posts` 인덱스에서 `id` window 만 keyset 으로 읽고
    - 2단계: 작은 `id` 집합에만 `author/board` projection 을 적용한다.
  - 즉 `id-first keyset + small projection join` 구조로 바뀌었다.

### 검증

- 단위/통합 테스트 갱신
  - [NotificationServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationServiceTest.java)
    - snapshot + atomic unread update
    - cutoff read-all
    - counter drift fallback 보정
  - [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java)
    - latest browse slice 가 `id window + batch projection + tag/image batch` 상한 안에 들어오는지 검증

- targeted 검증:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.service.NotificationServiceTest" \
  --tests "com.effectivedisco.service.NotificationAfterCommitIntegrationTest" \
  --tests "com.effectivedisco.service.PostListOptimizationIntegrationTest" \
  --tests "com.effectivedisco.controller.PostControllerTest"
```

- 전체 회귀:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon
```

둘 다 통과했다.

### 현재 상태

- 이번 단계는 `drift 원인 경로`를 직접 줄이는 코드 변경과 회귀 검증까지 완료한 상태다.
- 아직 clean `0.9 / 1시간 soak` 재측정은 하지 않았다.
- 따라서 현재 문서 기준 상태는:
  - 구현 완료
  - 테스트 통과
  - 다음 단계는 같은 clean 전용 DB 기준 soak 재측정

### 다음 액션

- clean `0.9 / 1시간 soak` 재측정
- 결과가 좋아지면 `0.9 / 2시간 soak`
- 여전히 drift 가 남으면 다음 우선순위는 `notification read-all UX 분리`와 browse latest 2차 축소

## 2026-03-25 notification/browse drift 완화 후 clean `0.9 / 1시간` 재측정

상태: 완료, `FAIL`

### 결과

- suite:
  [soak-20260325-155817.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-155817.md)
- server metrics:
  [soak-20260325-155817-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-155817-server.json)
- metrics timeline:
  [soak-20260325-155817-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260325-155817-metrics.jsonl)
- sql snapshot:
  [soak-20260325-155817-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-155817-sql.tsv)
- 최종 상태: `FAIL`
- `http p95 = 544.10ms`
- `http p99 = 1221.50ms`
- `unexpected_response_rate = 0.0026`
- `dbPoolTimeouts = 14967`
- SQL mismatch = 전부 `0`

주요 profile:

- `notification.read-all.summary avgWall = 94.13ms`, `avgSql = 93.17ms`
- `notification.store avgWall = 2.05ms`, `avgSql = 1.23ms`
- `post.list.browse.rows avgWall = 17.87ms`, `avgSql = 17.28ms`
- `post.list.search.rows avgWall = 11.83ms`, `avgSql = 11.29ms`
- `post.list.tag.rows avgWall = 2.09ms`, `avgSql = 1.67ms`

### 비교

- 비교 기준:
  [soak-20260325-141313.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313.md)
  와
  [soak-20260325-141313-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313-server.json)
- 핵심 변화:
  - `http p95 = 441.09ms -> 544.10ms`
  - `http p99 = 570.73ms -> 1221.50ms`
  - `unexpected_response_rate = 0.0000 -> 0.0026`
  - `dbPoolTimeouts = 0 -> 14967`
  - `notification.read-all.summary avgWall = 47.41ms -> 94.13ms`
  - `notification.store avgWall = 22.17ms -> 2.05ms`
  - `post.list.browse.rows avgWall = 15.52ms -> 17.87ms`

### 해석

- 이번 변경은 목적한 `notification.store` 개선에는 성공했다.
- 하지만 `cutoff id 기반 read-all` 전환 후 `notification.read-all.summary` 의 bulk update 가 장시간 soak 에서 더 비싸졌고,
  최종적으로는 전체 성능을 악화시켰다.
- 실제 장시간 metrics 와 slow query 상위는 대부분
  `update notifications ... set is_read=true where recipient_id=? and id<=cutoff`
  로 채워졌다.
- 즉 현재 regression 의 핵심은 `browse` 가 아니라 `notification read-all` 이다.

### 결론

- `User FOR UPDATE` 제거 자체는 나쁜 방향이 아니었지만,
  현재 `read-all transition` 구현은 clean `0.9 / 1시간 soak` 기준 실패다.
- 따라서 다음 단계는 browse 추가 최적화가 아니라,
  `notification read-all` 을 제품/UX 수준에서 줄이거나 명시 액션으로 분리하는 것이다.

## 2026-03-25 clean `0.9 / 15분` soak 확인

상태: 완료, `FAIL`

### 결과

- suite:
  [soak-20260325-171847.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-171847.md)
- server metrics:
  [soak-20260325-171847-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-171847-server.json)
- metrics timeline:
  [soak-20260325-171847-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260325-171847-metrics.jsonl)
- sql snapshot:
  [soak-20260325-171847-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-171847-sql.tsv)
- 최종 상태: `FAIL`
- `http p95 = 284.98ms`
- `http p99 = 513.80ms`
- `unexpected_response_rate = 0.0025`
- `dbPoolTimeouts = 4752`
- SQL mismatch = 전부 `0`

주요 profile:

- `notification.read-all.summary avgWall = 39.80ms`, `avgSql = 38.97ms`
- `notification.store avgWall = 1.69ms`, `avgSql = 0.94ms`
- `post.list.browse.rows avgWall = 4.71ms`, `avgSql = 4.19ms`
- `post.list.search.rows avgWall = 3.94ms`, `avgSql = 3.47ms`

### 해석

- `1시간`을 끝까지 가지 않아도 `15분` 시점부터 실패 방향이 이미 드러난다.
- 이 시점의 직접 원인은 `post.list`가 아니라 `notification.read-all.summary` 다.
- 하지만 당시 broad mixed baseline 은 여전히 내부 `read-all` action 을 주기적으로 호출하고 있었기 때문에,
  현실적인 baseline 과 `read-all` worst-case stress 가 분리되지 않은 상태였다.

## 2026-03-25 notification baseline/stress 분리 구현

상태: 구현 완료, 테스트 통과

### 문제 해결

- 웹 알림 UX
  - [NotificationWebController.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/controller/web/NotificationWebController.java)
  - [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java)
  - [NotificationRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/NotificationRepository.java)
  - [notifications/list.html](/home/admin0/effective-disco/src/main/resources/templates/notifications/list.html)
  - `read-all` 명시 액션도 recipient 전체 unread row bulk update 대신
    현재 페이지 batch 의 unread id 만 읽음 처리하도록 바꿨다.
  - 즉 웹 기준 의미는 이제 `현재 페이지 읽음`이다.

- loadtest baseline/stress 분리
  - [bbs-load.js](/home/admin0/effective-disco/loadtest/k6/bbs-load.js)
  - [LoadTestActionController.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestActionController.java)
  - [LoadTestBottleneckProfilingAspect.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestBottleneckProfilingAspect.java)
  - baseline:
    - `notification_read_write_mixed`
    - 현재 페이지 batch 읽음 경로 `/internal/load-test/actions/notifications/read-page`
    - `NOTIFICATION_BASELINE_READ_EVERY` 주기로만 읽음
  - stress:
    - `notification_read_all_stress`
    - 기존 `/internal/load-test/actions/notifications/read-all` worst-case 유지

### 검증

- 관련 회귀 테스트
  - [NotificationWebControllerTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/controller/web/NotificationWebControllerTest.java)
  - [NotificationServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationServiceTest.java)
  - [NotificationAfterCommitIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationAfterCommitIntegrationTest.java)
  - [LoadTestActionControllerTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/loadtest/LoadTestActionControllerTest.java)
- `k6 archive loadtest/k6/bbs-load.js -O /tmp/bbs-load.tar`
- `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon`

### 다음 액션

- clean `0.9 / 15분` baseline soak 재측정
- `notification_read_all_stress` 는 별도 stress 런으로만 해석

## 2026-03-25 notification baseline/stress short remeasure

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB를 baseline/stress 각각 실행 전 `DROP/CREATE`
- fresh `loadtest` 앱
- `0.9 / 5분 soak`, `30초 warmup`
- baseline:
  - `SCENARIO_PROFILE=notification`
  - `NOTIFICATION_MIXED_VUS=40`
  - `NOTIFICATION_STRESS_VUS=0`
- stress:
  - `SCENARIO_PROFILE=notification_stress`
  - `NOTIFICATION_MIXED_VUS=0`
  - `NOTIFICATION_STRESS_VUS=40`

### 결과

- baseline:
  [soak-20260325-180513.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-180513.md)
- baseline server:
  [soak-20260325-180513-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-180513-server.json)
- baseline SQL:
  [soak-20260325-180513-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-180513-sql.tsv)
- stress:
  [soak-20260325-181105.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-181105.md)
- stress server:
  [soak-20260325-181105-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-181105-server.json)
- stress SQL:
  [soak-20260325-181105-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-181105-sql.tsv)

숫자 비교:

- baseline
  - `status = FAIL`
  - `p95 = 93.57ms`
  - `p99 = 141.59ms`
  - `unexpected_response_rate = 0.0002`
  - `dbPoolTimeouts = 35`
  - `unreadNotificationMismatchUsers = 1`
- stress
  - `status = PASS`
  - `p95 = 121.51ms`
  - `p99 = 170.74ms`
  - `unexpected_response_rate = 0.0000`
  - `dbPoolTimeouts = 0`
  - `unreadNotificationMismatchUsers = 0`

주요 profile:

- baseline
  - `notification.read-page.summary avgWall = 28.25ms`, `avgSql = 27.25ms`
  - `notification.read-page.summary.transition avgWall = 26.92ms`, `avgSql = 26.06ms`
  - `notification.store avgWall = 9.30ms`, `avgSql = 8.76ms`
- stress
  - `notification.read-all.summary avgWall = 39.44ms`, `avgSql = 38.77ms`
  - `notification.read-all.summary.transition avgWall = 38.33ms`, `avgSql = 37.79ms`
  - `notification.store avgWall = 7.29ms`, `avgSql = 6.76ms`

### 해석

- `read-all` 이 절대 비용은 더 크지만, 이번 short remeasure 에서는
  새 baseline `read-page` 경로가 먼저 실패했다.
- 이건 두 가지 신호를 같이 보여준다.
  - `dbPoolTimeouts = 35`
  - `unreadNotificationMismatchUsers = 1`
- 즉 현재 문제는 “stress 만 위험하다”가 아니라,
  `markPageAsRead()` 경로가 짧은 soak 에서도 unread counter 정합성과 lock 경합을 동시에 흔든다는 점이다.
- 다음 최적화 우선순위는 `notification.read-page.summary.transition` 이다.

## 2026-03-25 notification `read-page` transition 최적화 + unread counter 정합성 통일

상태: 구현 완료, 검증 완료

### 문제

- `read-page` baseline 이 `read-all` stress 보다 먼저 깨졌다.
- 짧은 soak 에서도
  - `dbPoolTimeouts = 35`
  - `unreadNotificationMismatchUsers = 1`
  가 함께 발생했다.
- 즉 병목과 정합성 문제가 같은 경로에 동시에 있었다.

### 변경

- [NotificationRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/NotificationRepository.java)
  - `id/isRead` 만 읽는 얇은 read-page slice query 를 추가했다.
- [NotificationPageState.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/dto/response/NotificationPageState.java)
  - transition 전용 projection DTO 를 추가했다.
- [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java)
  - `NotificationResponse` 전체 대신 `NotificationPageState` slice 로 현재 페이지 상태를 읽도록 바꿨다.
  - `store/read-page/read-all` 세 mutation 경로를 recipient 직렬화 규칙 아래로 맞췄다.
  - unread counter 는 혼합 delta 대신 실제 unread row 수 refresh 로 통일했다.
- [UserRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/UserRepository.java)
  - unread counter refresh update 를 hot path 에서 직접 사용할 수 있게 했다.
- 관련 테스트:
  [NotificationServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationServiceTest.java),
  [NotificationAfterCommitIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationAfterCommitIntegrationTest.java)

### 검증

- targeted notification 회귀/동시성 테스트 통과
- 전체 `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon` 통과

### 재측정 결과

- clean baseline 재측정:
  [soak-20260325-185832.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-185832.md)
  - `PASS`
  - `dbPoolTimeouts = 0`
  - `unreadNotificationMismatchUsers = 0`
- clean stress 재측정:
  [soak-20260325-190524.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-190524.md)
  - `PASS`
  - `p95 = 106.12ms`
  - `p99 = 144.66ms`
  - `dbPoolTimeouts = 0`
  - `unreadNotificationMismatchUsers = 0`

### 해석

- notification 경로 단독 기준으로는 baseline/stress 모두 다시 안정 상태가 됐다.
- 즉 unread counter drift 와 `read-page` lock 경합은 이번 변경으로 사실상 해결된 것으로 본다.

## 2026-03-25 clean broad mixed `0.9 / 15분` 재측정

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=15m`
- `WARMUP_DURATION=30s`

### 결과

- suite:
  [soak-20260325-192358.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-192358.md)
- server:
  [soak-20260325-192358-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-192358-server.json)
- sql:
  [soak-20260325-192358-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-192358-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 244.17ms`
- `p99 = 311.62ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 1`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `notification.read-page.summary avgWall = 3.22ms`, `avgSql = 2.08ms`
- `notification.read-page.summary.transition avgWall = 2.83ms`, `avgSql = 1.88ms`
- `notification.store avgWall = 2.42ms`, `avgSql = 1.85ms`
- `post.list.browse.rows avgWall = 4.51ms`, `avgSql = 4.00ms`
- `post.list.search.rows avgWall = 3.75ms`, `avgSql = 3.30ms`

### 해석

- broad mixed 의 남은 실패 원인은 더 이상 notification 경로가 아니다.
- notification profile 은 상당히 내려왔고, `dbPoolTimeouts` 와 `unread counter mismatch` 도 `0` 이다.
- 이번 failure 는 `duplicateKeyConflicts = 1` 하나로 좁혀졌다.
- 따라서 다음 최적화/조사는 notification 이 아니라
  relation write 중 어떤 경로가 중복 키 예외를 발생시키는지 추적하는 쪽이 맞다.

## 2026-03-25 duplicate-key 경로별 메트릭 추가 + broad mixed 재측정

상태: 구현 완료, 측정 완료

### 문제

- clean broad mixed `0.9 / 15분` 재측정에서
  - `dbPoolTimeouts = 0`
  - `unreadNotificationMismatchUsers = 0`
  - `duplicateKeyConflicts = 1`
  만 남았다.
- 총량 카운터만으로는 어떤 write path 가 문제인지 알 수 없었다.

### 변경

- [GlobalExceptionHandler.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/config/GlobalExceptionHandler.java)
  - duplicate-key 예외 기록 시 request method/path 를 함께 넘기도록 변경했다.
- [LoadTestMetricsService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestMetricsService.java)
  - 숫자 ID path segment 를 일반화한 request signature 생성
  - constraint 이름 추출
  - duplicate-key profile 누적
- [LoadTestMetricsSnapshot.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestMetricsSnapshot.java)
  - `duplicateKeyConflictProfiles` 추가
- [LoadTestDuplicateKeyConflictSnapshot.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestDuplicateKeyConflictSnapshot.java)
  - 경로/constraint/건수/sample message snapshot 추가
- 테스트:
  [LoadTestMetricsServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/loadtest/LoadTestMetricsServiceTest.java),
  [LoadTestMetricsControllerTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/loadtest/LoadTestMetricsControllerTest.java)

### 검증

- targeted loadtest metrics 테스트 통과

### 재측정 결과

- suite:
  [soak-20260325-194808.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-194808.md)
- server:
  [soak-20260325-194808-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-194808-server.json)
- sql:
  [soak-20260325-194808-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-194808-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 243.57ms`
- `p99 = 310.13ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 1`
- `unreadNotificationMismatchUsers = 0`

추적 결과:

- `requestSignature = POST /api/posts`
- `constraintName = ukt48xdq560gs3gap9g7jg36kgc`
- sample message:
  `Key (name)=(write) already exists.`

### 해석

- broad mixed 의 남은 failure 는 relation 토글 경로가 아니라 게시물 생성 시 태그 생성 race 였다.
- 충돌 대상은 [Tag.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/domain/Tag.java#L17) 의 `tags.name` 유니크 제약이다.
- 실제 race 지점은 [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java#L784) 의 `resolveTagsForWrite()` 이다.
- 현재 구현은 `findAllByNameIn()` 뒤 missing tag 를 `saveAll()` 하므로, broad mixed 의 `writePostsAndComments()` 가 같은 태그 `write` 를 동시에 생성할 때 duplicate-key 가 발생한다.
- 따라서 다음 최적화 1순위는 relation write 가 아니라 `tag resolve/create` 를 멱등화하는 것이다.

## 2026-03-25 태그 해석 멱등화

상태: 구현 완료

### 문제

- clean broad mixed `0.9 / 15분` 재측정에서
  - `duplicateKeyConflicts = 1`
  - `requestSignature = POST /api/posts`
  - `constraint = tags.name unique`
  로 좁혀졌다.
- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java#L784) 의
  `resolveTagsForWrite()` 는
  `findAllByNameIn() -> missing tag saveAll()` 구조라서
  같은 새 태그를 동시에 만들 때 duplicate-key race 가 났다.

### 변경

- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - missing tag 를 직접 저장하지 않고,
    태그 생성 전용 서비스 호출 뒤 최종 태그 집합을 다시 조회하도록 변경했다.
- [TagWriteService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/TagWriteService.java)
  - missing tag 생성만 짧은 새 트랜잭션으로 분리했다.
  - duplicate-key 는 흡수하고, 최종 재조회에서 이미 생성된 태그를 합치게 했다.
- 테스트:
  - [WritePathConcurrencyTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/WritePathConcurrencyTest.java)
  - [PostServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostServiceTest.java)
  - [PostCreateOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostCreateOptimizationIntegrationTest.java)

### 검증

- 같은 새 태그로 게시물 작성 `2건`을 동시에 실행해도
  태그 row 는 `1개`, 게시물은 `2개` 모두 생성되는 회귀 테스트를 추가했다.
- 전체 `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon` 통과

### 해석

- broad mixed 에 남아 있던 마지막 duplicate-key failure 는
  relation write 가 아니라 태그 생성 race 였다.
- 이번 변경은 게시물 작성 경로를 실패시키던 unique 충돌을
  게시물 생성 성공 + 최종 태그 재조회 모델로 바꿨다.

## 2026-03-25 clean broad mixed `0.9 / 15분` 재재측정

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=15m`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260325-203507.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507.md)
- server:
  [soak-20260325-203507-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507-server.json)
- sql:
  [soak-20260325-203507-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507-sql.tsv)

숫자:

- `status = PASS`
- `p95 = 246.53ms`
- `p99 = 314.19ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `notification.read-page.summary.transition avgWall = 2.93ms`, `avgSql = 1.98ms`
- `notification.store avgWall = 2.53ms`, `avgSql = 1.95ms`
- `post.list.browse.rows avgWall = 4.53ms`, `avgSql = 4.02ms`
- `post.list.search.rows avgWall = 3.76ms`, `avgSql = 3.31ms`

### 해석

- 태그 생성 race 수정 후, broad mixed `0.9 / 15분` 은 clean baseline 기준 다시 `PASS` 가 됐다.
- `duplicateKeyConflicts`, `dbPoolTimeouts`, `unread counter mismatch` 가 모두 `0` 으로 정리됐다.
- 즉 현재 broad mixed 의 최신 clean 기준선은
  “notification recovery + tag idempotency” 이후 정상 상태로 회복된 것으로 본다.

## 2026-03-25 clean broad mixed `0.9 / 1시간` 재측정

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=1h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260325-234707.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-234707.md)
- server:
  [soak-20260325-234707-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-234707-server.json)
- sql:
  [soak-20260325-234707-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-234707-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 321.60ms`
- `p99 = 422.03ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 1`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `notification.read-page.summary.transition avgWall = 2.86ms`, `avgSql = 1.86ms`
- `notification.store avgWall = 3.38ms`, `avgSql = 2.76ms`
- `post.list.browse.rows avgWall = 16.10ms`, `avgSql = 15.55ms`
- `post.list.search.rows avgWall = 10.88ms`, `avgSql = 10.39ms`
- `post.like.add avgWall = 5.98ms`, `avgSql = 5.31ms`
- `post.like.remove avgWall = 5.90ms`, `avgSql = 5.22ms`

### 해석

- 최신 clean `1시간` soak 의 직접 failure 원인은 `dbPoolTimeouts = 1` 한 건뿐이었다.
- `notification` 경로와 `Tag.name` duplicate-key 는 더 이상 실패 원인이 아니었다.
- 즉 남은 문제는 정합성보다 `장시간 broad mixed` 에서의 희박한 pool saturation 이고,
  다음 판단은 같은 조건 `1시간`을 반복 재측정해 이 1건이 재현성 있는 경계인지 확인하는 쪽이 맞다.

## 2026-03-26 clean broad mixed `0.9 / 1시간` 반복 재측정

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=1h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-010247.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-010247.md)
- server:
  [soak-20260326-010247-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-010247-server.json)
- sql:
  [soak-20260326-010247-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-010247-sql.tsv)

숫자:

- `status = PASS`
- `p95 = 305.41ms`
- `p99 = 390.86ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `notification.read-page.summary.transition avgWall = 2.86ms`, `avgSql = 1.86ms`
- `notification.store avgWall = 3.24ms`, `avgSql = 2.61ms`
- `post.list.browse.rows avgWall = 15.66ms`, `avgSql = 15.12ms`
- `post.list.search.rows avgWall = 10.67ms`, `avgSql = 10.17ms`
- `post.like.add avgWall = 5.89ms`, `avgSql = 5.22ms`
- `post.like.remove avgWall = 5.81ms`, `avgSql = 5.14ms`

### 해석

- 반복 재측정에서는 `dbPoolTimeouts = 0` 으로 돌아와 `PASS` 했다.
- 따라서 직전 `dbPoolTimeouts = 1` 은 지금까지는 재현성 있는 한계보다
  단발성 노이즈로 보는 쪽이 맞다.
- 다만 `maxThreadsAwaitingConnection = 200` 수준의 대기 압력은 그대로이므로,
  `0.9 / 1시간`은 통과했지만 여유가 큰 구간은 아니라는 해석이 맞다.

## 2026-03-26 clean broad mixed `0.9 / 2시간` soak

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-021537.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-021537.md)
- server:
  [soak-20260326-021537-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-021537-server.json)
- sql:
  [soak-20260326-021537-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-021537-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 706.22ms`
- `p99 = 991.52ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 380`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `35분`: `dbPoolTimeouts = 17`
- `55분`: `dbPoolTimeouts = 19`
- `1시간`: `dbPoolTimeouts = 31`
- `1시간 25분`: `dbPoolTimeouts = 328`
- `최종`: `dbPoolTimeouts = 380`

주요 profile:

- `notification.read-page.summary.transition avgWall = 2.77ms`, `avgSql = 1.77ms`
- `notification.store avgWall = 5.68ms`, `avgSql = 5.05ms`
- `post.list.browse.rows avgWall = 32.32ms`, `avgSql = 31.75ms`
- `post.list.search.rows avgWall = 20.62ms`, `avgSql = 20.10ms`
- `post.like.add avgWall = 5.18ms`, `avgSql = 4.48ms`
- `post.like.remove avgWall = 5.11ms`, `avgSql = 4.41ms`

### 해석

- `0.9 / 2시간`에서는 장시간 broad mixed read pressure가 누적되며
  pool timeout 이 재현성 있게 증가했다.
- 반면 `duplicateKeyConflicts`, `unread counter mismatch`, SQL 정합성 mismatch 는 모두 `0` 으로 유지됐다.
- 따라서 현재 남은 문제는 정합성이 아니라
  `0.9` 부하에서 `2시간`을 버티지 못하는 장시간 saturation 이다.

## 2026-03-26 clean broad mixed `0.85 / 2시간` soak

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.85`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-042905.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-042905.md)
- server:
  [soak-20260326-042905-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-042905-server.json)
- sql:
  [soak-20260326-042905-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-042905-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 519.43ms`
- `p99 = 1103.31ms`
- `unexpected_response_rate = 0.0001`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 956`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `35분`: `dbPoolTimeouts = 0`
- `1시간`: `dbPoolTimeouts = 0`
- `1시간 30분`: `dbPoolTimeouts = 6`
- `1시간 45분`: `dbPoolTimeouts = 48`
- `1시간 55분`: `dbPoolTimeouts = 363`
- `최종`: `dbPoolTimeouts = 956`

주요 profile:

- `notification.read-page.summary.transition avgWall = 2.83ms`, `avgSql = 1.81ms`
- `notification.store avgWall = 5.76ms`, `avgSql = 5.11ms`
- `post.list.browse.rows avgWall = 30.52ms`, `avgSql = 29.94ms`
- `post.list.search.rows avgWall = 19.74ms`, `avgSql = 19.22ms`
- `post.like.add avgWall = 5.48ms`, `avgSql = 4.78ms`
- `post.like.remove avgWall = 5.43ms`, `avgSql = 4.73ms`

### 해석

- `0.85`는 `0.9`보다 후반부까지 더 오래 버티지만,
  `2시간` 기준에서는 결국 pool timeout 이 누적되며 실패했다.
- 정합성 쪽 문제는 끝까지 보이지 않았으므로,
  현재 남은 과제는 여전히 장시간 read pressure 에 의한 saturation 이다.

## 2026-03-26 clean broad mixed `0.8 / 2시간` soak

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.8`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-073715.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-073715.md)
- server:
  [soak-20260326-073715-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-073715-server.json)
- sql:
  [soak-20260326-073715-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-073715-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 364.85ms`
- `p99 = 499.58ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 8`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `1시간 40분`: `dbPoolTimeouts = 0`
- `1시간 45분`: `dbPoolTimeouts = 1`
- `1시간 50분`: `dbPoolTimeouts = 2`
- `1시간 55분`: `dbPoolTimeouts = 4`
- `최종`: `dbPoolTimeouts = 8`

주요 profile:

- `notification.read-page.summary.transition avgWall = 2.74ms`, `avgSql = 1.72ms`
- `notification.store avgWall = 4.42ms`, `avgSql = 3.79ms`
- `post.list.browse.rows avgWall = 28.15ms`, `avgSql = 27.58ms`
- `post.list.search.rows avgWall = 18.27ms`, `avgSql = 17.75ms`
- `post.like.add avgWall = 5.75ms`, `avgSql = 5.06ms`
- `post.like.remove avgWall = 5.72ms`, `avgSql = 5.02ms`

### 해석

- `0.8`도 strict PASS 기준으로는 `2시간`을 끝까지 버티지 못했다.
- 하지만 timeout 누적은 `0.85`, `0.9`보다 훨씬 작았고,
  현재 2시간 stable factor 후보로는 가장 근접한 구간이다.
- 즉 다음 단계는 `0.75 / 2시간`로 안정 구간을 확정하거나,
  `0.8`을 목표 기준으로 추가 최적화를 하는 것 중 하나다.

## 2026-03-26 `post.list.browse.rows`, `post.list.search.rows`, `notification.store` 최적화

상태: 구현 및 clean short soak 검증 완료

### 변경 배경

- `0.8 / 2시간`, `0.85 / 2시간`, `0.9 / 2시간` soak 추세에서
  장시간 drift의 핵심은 `post.list.browse.rows`, `post.list.search.rows`, `notification.store`였다.
- 여기서 중요한 점은 개별 경로의 1% 개선보다,
  hot path 전체 data flow를 줄이면서 다른 모듈을 악화시키지 않는 변경이어야 한다는 것이다.
- 이번 변경은 다음 trade-off를 피하는 데 초점을 뒀다.
  - browse/search를 빠르게 만들겠다고 응답 계약을 줄여 웹/API 소비자를 깨는 것
  - notification.store를 빠르게 만들겠다고 unread counter 정합성을 희생하는 것

### data flow 분석 결과

- `post.list.browse.rows`
  - latest browse는 이미 `id window -> row projection` 2단계였지만,
    작은 id 집합 projection에서도 `boards` join을 계속 타고 있었다.
  - board-scoped browse 요청은 `boardSlug`로 이미 게시판이 확정되므로,
    row마다 board name/slug를 DB에서 다시 읽는 것은 중복이었다.
- `post.list.search.rows`
  - board-scoped keyword search도 같은 문제를 가졌다.
  - 서비스는 이미 `Board`를 들고 있는데,
    native query는 `boards` join으로 같은 `name/slug`를 다시 붙였다.
- `notification.store`
  - notification mutation의 consistency 모델은 유지해야 한다.
  - 하지만 store 경로는 `recipient 잠금 -> insert -> unread COUNT(*) refresh -> after-commit unread 재조회`로
    직렬화 비용 외에 추가 count/read 비용이 있었다.
  - store는 같은 recipient 잠금 아래에서 실행되므로
    `현재 unread snapshot + 1`이 이미 정확한 확정값이다.

### 구현 내용

- board-scoped browse rows
  - [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - latest browse의 small id batch projection에서 `boards` join을 제거했다.
  - likes/comments browse도 board-scoped 전용 projection을 추가해 `posts + author`만 읽게 했다.
  - board name/slug는 서비스 레이어에서 이미 확보한 `Board`로 주입한다.
- board-scoped search rows
  - [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  - PostgreSQL/H2 fallback keyword search row/slice query에서
    board-scoped 요청은 `boards` join 없이 `:boardName`, `:boardSlug`를 직접 select 하도록 바꿨다.
  - count query와 slice query가 서로 다른 named parameter 집합을 가지므로,
    실제로 존재하는 parameter에만 값을 바인딩하도록 정리했다.
- notification.store
  - [UserRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/UserRepository.java)
  - [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java)
  - `findByUsernameForUpdate(User entity)` 대신
    `NotificationRecipientSnapshot FOR UPDATE` 경로를 추가했다.
  - store는 `save -> incrementUnreadNotificationCount -> after-commit exact unread push`로 마무리한다.
  - 즉 store hot path에서 `refreshUnreadNotificationCount()`와 `getUnreadCount()` 재조회가 빠졌다.

### 검증

- targeted test
  - `NotificationServiceTest`
  - `NotificationAfterCommitIntegrationTest`
  - `PostListOptimizationIntegrationTest`
  - `PostControllerTest`
- full test
  - `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon`
- clean short soak
  - [soak-20260326-113432.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-113432.md)
  - [soak-20260326-113432-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-113432-server.json)

### 결과

- `status = PASS`
- `http p95 = 221.28ms`
- `http p99 = 282.98ms`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `post.list.browse.rows avgWall = 1.89ms`, `avgSql = 1.45ms`, `avgSqlStatementCount = 1.22`
- `post.list.search.rows avgWall = 2.03ms`, `avgSql = 1.57ms`, `avgSqlStatementCount = 1.0`
- `notification.store avgWall = 2.02ms`, `avgSql = 1.29ms`, `avgSqlStatementCount = 3.0`
- `notification.read-page.summary.transition avgWall = 2.76ms`, `avgSql = 1.78ms`

### 해석

- 이번 변경은 응답 계약과 notification consistency를 유지한 채
  `browse/search/store` hot path의 중복 data flow를 직접 줄였다.
- 특히 board-scoped browse/search에서 `boards` join을 제거한 것은
  게시판 정보가 이미 요청 컨텍스트에 있을 때만 적용되는 국소 최적화라
  다른 목록 경로를 악화시키지 않는다.
- notification.store는 consistency 모델을 유지한 채
  `COUNT(*) refresh + after-commit unread 재조회`를 제거했기 때문에,
  read-page/read-all 경로의 exact refresh 전략과도 충돌하지 않는다.

## 2026-03-26 clean broad mixed `0.8 / 2시간` 재측정 after browse/search/store 최적화

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.8`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-114456.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-114456.md)
- server:
  [soak-20260326-114456-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-114456-server.json)
- sql:
  [soak-20260326-114456-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-114456-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 385.38ms`
- `p99 = 593.96ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 179`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `1시간 25분`: `dbPoolTimeouts = 0`
- `1시간 30분`: `dbPoolTimeouts = 4`
- `1시간 35분`: `dbPoolTimeouts = 20`
- `1시간 40분`: `dbPoolTimeouts = 48`
- `1시간 45분`: `dbPoolTimeouts = 144`
- `1시간 50분`: `dbPoolTimeouts = 179`
- `최종`: `dbPoolTimeouts = 179`

주요 profile:

- `post.list.browse.rows avgWall = 23.97ms`, `avgSql = 23.41ms`
- `post.list.search.rows avgWall = 18.80ms`, `avgSql = 18.23ms`
- `notification.store avgWall = 2.39ms`, `avgSql = 1.53ms`
- `notification.read-page.summary.transition avgWall = 3.22ms`, `avgSql = 2.08ms`

### strict same-condition 전후 비교

- 비교 기준:
  [soak-20260326-073715.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-073715.md)
  대비
  [soak-20260326-114456.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-114456.md)
- `post.list.browse.rows avgWall = 28.15ms -> 23.97ms`, `14.9% 개선`
- `post.list.browse.rows avgSql = 27.58ms -> 23.41ms`, `15.1% 개선`
- `post.list.search.rows avgWall = 18.27ms -> 18.80ms`, `2.9% 악화`
- `post.list.search.rows avgSql = 17.75ms -> 18.23ms`, `2.7% 악화`
- `notification.store avgWall = 4.42ms -> 2.39ms`, `45.9% 개선`
- `notification.store avgSql = 3.79ms -> 1.53ms`, `59.5% 개선`
- `notification.read-page.summary.transition avgWall = 2.74ms -> 3.22ms`, `17.7% 악화`
- `http p95 = 364.85ms -> 385.38ms`, `5.6% 악화`
- `http p99 = 499.58ms -> 593.96ms`, `18.9% 악화`
- `dbPoolTimeouts = 8 -> 179`, `22.4배 악화`

### 해석

- 이번 변경은 `notification.store`와 browse latest 계열에는 국소적으로 이득이 있었다.
- 하지만 broad mixed `0.8 / 2시간`이라는 동일 조건으로 보면
  전체 결과는 regression 으로 보는 게 맞다.
- 즉 다음 최적화는 `notification.store` 추가 미세 조정보다
  `post.list.browse.rows` 내부의 `latest/likes/comments` 분해와
  ranked browse 경로 최적화에 초점을 두는 것이 합리적이다.

## 2026-03-26 ranked browse(`likes/comments`) `id-first` 최적화

상태: 구현 및 clean short soak 검증 완료

### 변경 배경

- strict same-condition `0.8 / 2시간` 비교에서
  `notification.store`는 크게 개선됐지만 broad mixed 전체는 regression이었다.
- 남은 장시간 drift의 1순위는 `post.list.browse.rows`였고,
  latest browse는 이미 `id-first -> small row batch`인데
  ranked browse(`likes/comments`)는 아직 정렬 + author projection을 한 SQL에서 같이 처리하고 있었다.
- 이 비대칭 때문에 latest만 빨라지고 likes/comments browse는 계속 긴 soak에서 풀 점유를 키우는 구조였다.

### data flow 분석 결과

- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)의
  `loadRankedBrowseSlice()`는
  [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  의 ranked projection slice를 바로 호출했다.
- 즉 ranked browse는
  `정렬 + board filter + author join projection`
  을 한 번에 처리했고,
  latest browse가 이미 제거한 join/정렬 동시 비용이 그대로 남아 있었다.
- 응답 계약 측면에서는 `nextCursorSortValue`, `nextCursorCreatedAt`, `nextCursorId`만 유지하면 되므로,
  내부를 `id-first`로 바꿔도 API/SSR 소비자를 깨지 않는다.

### 구현 내용

- [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - ranked browse 전용 id window query 4개를 추가했다.
    - `findScrollPostIdsByBoardOrderByLikeCountDesc`
    - `findScrollPostIdsByBoardAndLikeCountAfter`
    - `findScrollPostIdsByBoardOrderByCommentCountDesc`
    - `findScrollPostIdsByBoardAndCommentCountAfter`
- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - `loadRankedBrowseSlice()`를 latest와 같은 구조로 바꿨다.
  - 즉 `ranked id window -> small id batch projection -> withBoardContext` 순서로 통일했다.
  - latest/ranked가 같은 `toBoardScopedSlice()`로 수렴하게 정리했다.
- [Post.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/domain/Post.java)
  - ranked browse용 인덱스 2개를 추가했다.
    - `idx_post_board_draft_like_created_id_desc`
    - `idx_post_board_draft_comment_created_id_desc`

### module dependency / 부작용 검토

- [PostController.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/controller/PostController.java)
  의 `/api/posts/slice` 계약은 유지된다.
- `nextCursorSortValue` 계산은 마지막 row의 `likeCount` / `commentCount`를 그대로 사용하므로
  클라이언트 cursor 의미가 바뀌지 않는다.
- SSR/page 기반 `Page<PostResponse>` 경로는 건드리지 않았고,
  이번 변경은 board-scoped slice browse 내부 구현에만 국한된다.
- latest browse에서 이미 쓰던 `small id batch + board context 주입` 패턴을 재사용했기 때문에,
  새로운 데이터 흐름을 도입하기보다 기존 검증된 구조를 ranked browse까지 확장한 형태다.

### 검증

- targeted test
  - [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java)
  - [PostControllerTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/controller/PostControllerTest.java)
- full test
  - `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon`
- clean short soak
  - [soak-20260326-142343.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-142343.md)
  - [soak-20260326-142343-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-142343-server.json)

### 결과

- `status = PASS`
- `http p95 = 220.33ms`
- `http p99 = 281.45ms`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `post.list.browse.rows avgWall = 1.63ms`, `avgSql = 0.72ms`, `avgSqlStatementCount = 2.0`
- `post.list.search.rows avgWall = 2.77ms`, `avgSql = 2.31ms`, `avgSqlStatementCount = 1.0`
- `notification.store avgWall = 2.06ms`, `avgSql = 1.34ms`, `avgSqlStatementCount = 3.0`

### 해석

- 이번 변경은 ranked browse를 latest와 동일한 data flow 구조로 맞춰
  board-scoped browse 경로의 내부 비대칭을 제거했다.
- short baseline 기준으로는 회귀 없이 통과했고,
  다음 평가는 `0.8 / 2시간` long-run에서 browse/search drift가 실제로 줄어드는지로 이어진다.

## 2026-03-26 clean broad mixed `0.8 / 2시간` 재측정 after ranked browse 최적화

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.8`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-143758.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-143758.md)
- server:
  [soak-20260326-143758-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-143758-server.json)
- sql:
  [soak-20260326-143758-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-143758-sql.tsv)

숫자:

- `status = PASS`
- `p95 = 228.53ms`
- `p99 = 297.84ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `5분`: `dbPoolTimeouts = 0`
- `30분`: `dbPoolTimeouts = 0`
- `60분`: `dbPoolTimeouts = 0`
- `90분`: `dbPoolTimeouts = 0`
- `120분`: `dbPoolTimeouts = 0`

주요 profile:

- `post.list.browse.rows avgWall = 1.76ms`, `avgSql = 0.74ms`
- `post.list.search.rows avgWall = 17.75ms`, `avgSql = 17.24ms`
- `notification.store avgWall = 2.17ms`, `avgSql = 1.38ms`
- `notification.read-page.summary.transition avgWall = 2.95ms`, `avgSql = 1.86ms`

### strict same-condition 전후 비교

- 비교 기준:
  [soak-20260326-114456.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-114456.md)
  대비
  [soak-20260326-143758.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-143758.md)
- `http p95 = 385.38ms -> 228.53ms`, `40.7% 개선`
- `http p99 = 593.96ms -> 297.84ms`, `49.9% 개선`
- `dbPoolTimeouts = 179 -> 0`, `100% 개선`
- `post.list.browse.rows avgWall = 23.97ms -> 1.76ms`, `92.7% 개선`
- `post.list.browse.rows avgSql = 23.41ms -> 0.74ms`, `96.9% 개선`
- `post.list.search.rows avgWall = 18.80ms -> 17.75ms`, `5.6% 개선`
- `post.list.search.rows avgSql = 18.23ms -> 17.24ms`, `5.4% 개선`
- `notification.store avgWall = 2.39ms -> 2.17ms`, `9.5% 개선`
- `notification.store avgSql = 1.53ms -> 1.38ms`, `10.0% 개선`
- `notification.read-page.summary.transition avgWall = 3.22ms -> 2.95ms`, `8.6% 개선`
- `notification.read-page.summary.transition avgSql = 2.08ms -> 1.86ms`, `10.6% 개선`

### 원인 분석

- 가장 큰 변화는 [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  의 `loadRankedBrowseSlice()`와
  [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  의 ranked browse query shape였다.
- 이전 ranked browse는 `정렬 + author/board projection`을 한 번에 처리했고,
  board-scoped latest browse가 이미 제거한 join/정렬 동시 비용을 그대로 안고 있었다.
- 이번 변경은 ranked browse도 latest와 같은 `id-first -> small row batch`로 바꾸고,
  [Post.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/domain/Post.java)
  의 정렬별 복합 인덱스로 window 추출을 바로 받도록 만들었다.
- 그 결과 broad mixed long-run에서 가장 비쌌던 `post.list.browse.rows`의
  커넥션 점유 시간이 거의 사라졌고,
  이 국소 개선이 전체 `p95/p99`와 `dbPoolTimeouts` 감소로 이어졌다.

### 최적화 교훈

- `pool size`나 timeout 설정을 만지기 전에, 실제로 커넥션을 오래 잡는 `핫 read path`의 data flow를 바꾸는 것이 훨씬 강하다.
- `정렬 + projection`을 한 SQL에 몰아넣는 구조보다,
  `인덱스로 작은 id window를 먼저 자르고 그 다음 projection` 하는 구조가 장시간 soak에서 훨씬 안정적이다.
- 가장 중요한 건 `국소 1% 개선`이 아니라 `시스템 전체 strict baseline`을 움직이는 변경이다.
  이번 ranked browse 최적화는 바로 그 경우였다.
- 부작용을 줄이는 방법도 확인됐다.
  API/SSR 계약은 유지하고, 서비스/리포지토리 내부 data flow만 바꿔도 큰 개선을 낼 수 있다.

## 2026-03-26 clean broad mixed `0.85 / 2시간` 재측정 after ranked browse 최적화

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` 앱
- `SOAK_FACTOR=0.85`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`

### 결과

- suite:
  [soak-20260326-170952.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-170952.md)
- server:
  [soak-20260326-170952-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-170952-server.json)
- sql:
  [soak-20260326-170952-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-170952-sql.tsv)

숫자:

- `status = PASS`
- `p95 = 244.29ms`
- `p99 = 314.73ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `80분`: `dbPoolTimeouts = 0`
- `85분`: `dbPoolTimeouts = 0`
- `90분`: `dbPoolTimeouts = 0`
- `95분`: `dbPoolTimeouts = 0`
- `100분`: `dbPoolTimeouts = 0`
- `105분`: `dbPoolTimeouts = 0`
- `110분`: `dbPoolTimeouts = 0`
- `115분`: `dbPoolTimeouts = 0`
- `120분`: `dbPoolTimeouts = 0`

주요 profile:

- `post.list.browse.rows avgWall = 1.79ms`, `avgSql = 0.76ms`
- `post.list.search.rows avgWall = 19.01ms`, `avgSql = 18.49ms`
- `notification.store avgWall = 2.24ms`, `avgSql = 1.44ms`
- `notification.read-page.summary.transition avgWall = 3.07ms`, `avgSql = 1.95ms`

### strict same-condition 전후 비교

- 비교 기준:
  [soak-20260326-042905.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-042905.md)
  대비
  [soak-20260326-170952.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-170952.md)
- `http p95 = 519.43ms -> 244.29ms`, `53.0% 개선`
- `http p99 = 1103.31ms -> 314.73ms`, `71.5% 개선`
- `dbPoolTimeouts = 956 -> 0`, `100% 개선`
- `post.list.browse.rows avgWall = 30.52ms -> 1.79ms`, `94.1% 개선`
- `post.list.browse.rows avgSql = 29.94ms -> 0.76ms`, `97.5% 개선`
- `post.list.search.rows avgWall = 19.74ms -> 19.01ms`, `3.7% 개선`
- `post.list.search.rows avgSql = 19.22ms -> 18.49ms`, `3.8% 개선`
- `notification.store avgWall = 5.76ms -> 2.24ms`, `61.2% 개선`
- `notification.store avgSql = 5.11ms -> 1.44ms`, `71.8% 개선`
- `notification.read-page.summary.transition avgWall = 2.83ms -> 3.07ms`, `8.2% 악화`

### 원인 분석

- ranked browse 최적화가 `0.8 / 2시간`에서만 통했던 것이 아니라
  바로 위 factor인 `0.85 / 2시간`에서도 그대로 유지됐다.
- 전체 결과를 움직인 건 여전히 `post.list.browse.rows`의 급감이다.
  즉 `board scoped likes/comments browse`의 window 추출을 인덱스로 먼저 받고,
  projection join을 작은 id 집합으로 제한한 것이 핵심이다.
- `post.list.search.rows`는 여전히 가장 큰 read drift 경로다.
  다만 현재 구조에선 browse가 더 이상 pool을 먼저 포화시키지 않아서,
  search drift가 `0.85 / 2시간` PASS를 깨뜨리지는 못했다.

### 이번 결과의 교훈

- 장시간 기준선은 가장 비싼 read path 하나만 바로잡아도 크게 올라갈 수 있다.
- `browse`와 `search`가 동시에 존재하더라도,
  먼저 더 비싼 `browse`를 지우면 전체 pool saturation이 급격히 줄어든다.
- 국소 지표 중 일부가 완벽하지 않아도 된다.
  이번에는 `notification.read-page.summary.transition`이 소폭 악화됐지만,
  시스템 전체 결과는 오히려 크게 개선됐다.

## 2026-03-26 clean broad mixed `0.9 / 2시간` 재측정 after ranked browse 최적화

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` app
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`
- `SAMPLE_INTERVAL_SECONDS=300`

### 결과

- suite:
  [soak-20260326-194048.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048.md)
- k6:
  [soak-20260326-194048-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048-k6.json)
- server:
  [soak-20260326-194048-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048-server.json)
- sql:
  [soak-20260326-194048-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048-sql.tsv)

숫자:

- `status = FAIL`
- `p95 = 259.81ms`
- `p99 = 331.91ms`
- `unexpected_response_rate = 0.00038%`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 64`
- `unreadNotificationMismatchUsers = 0`

5분 모니터링 요약:

- `5분`: `dbPoolTimeouts = 64`
- `30분`: `dbPoolTimeouts = 64`
- `60분`: `dbPoolTimeouts = 64`
- `90분`: `dbPoolTimeouts = 64`
- `120분`: `dbPoolTimeouts = 64`

주요 profile:

- `post.list.browse.rows avgWall = 1.80ms`, `avgSql = 0.77ms`
- `post.list.search.rows avgWall = 20.37ms`, `avgSql = 19.84ms`
- `notification.store avgWall = 2.28ms`, `avgSql = 1.46ms`
- `notification.read-page.summary.transition avgWall = 3.09ms`, `avgSql = 2.02ms`

### strict same-factor 전후 비교

- 비교 기준:
  [soak-20260326-021537.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-021537.md)
  대비
  [soak-20260326-194048.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048.md)
- `http p95 = 706.22ms -> 259.81ms`, `63.2% 개선`
- `http p99 = 991.52ms -> 331.91ms`, `66.5% 개선`
- `dbPoolTimeouts = 380 -> 64`, `83.2% 개선`
- `post.list.browse.rows avgWall = 32.32ms -> 1.80ms`, `94.4% 개선`
- `post.list.browse.rows avgSql = 31.76ms -> 0.77ms`, `97.6% 개선`
- `post.list.search.rows avgWall = 20.62ms -> 20.37ms`, `1.2% 개선`
- `post.list.search.rows avgSql = 20.10ms -> 19.84ms`, `1.3% 개선`
- `notification.store avgWall = 5.68ms -> 2.28ms`, `59.9% 개선`
- `notification.store avgSql = 4.93ms -> 1.46ms`, `70.4% 개선`

### 원인 분석

- ranked browse 최적화는 `0.9 / 2시간` strict 기준을 아직 완전히 통과시키진 못했지만,
  실패 양상을 `후반 drift`에서 `초기 timeout burst`로 바꾸는 데는 성공했다.
- `post.list.browse.rows`는 사실상 더 이상 병목이 아니고,
  장시간 누적 profile 기준 남은 가장 큰 read drift는 `post.list.search.rows`다.
- 이번 run의 `dbPoolTimeouts=64`는 `5분` 시점에 이미 전부 발생했고
  이후 증가하지 않았으므로,
  남은 과제는 steady-state saturation보다 startup 직후 connection contention 완화에 가깝다.

### 최적화 교훈

- 가장 비싼 hot path를 줄이면 장시간 soak는 물론 초기 saturation 양상도 크게 바뀔 수 있다.
- 다만 strict long-run PASS를 만들려면 steady-state 경로뿐 아니라
  warmup 직후의 burst 구간까지 따로 봐야 한다.
- 이번 결과는 `browse`가 해결된 뒤 남은 다음 타깃이
  `search rows`와 초기 구간 contention이라는 점을 분명하게 보여준다.

## 2026-03-26~27 initial-burst 분리 계측

상태: 완료

### 구현

- runner:
  [run-bbs-initial-burst.sh](/home/admin0/effective-disco/loadtest/run-bbs-initial-burst.sh)

의도:

- 기존 [run-bbs-soak.sh](/home/admin0/effective-disco/loadtest/run-bbs-soak.sh)
  전체 흐름(cleanup, SQL snapshot, metrics timeline)은 그대로 재사용하고,
  `5분 soak + 30초 샘플링` 조건만 쉽게 반복 실행하게 한다.
- `0.9 / 2시간` 실패 런에서 보였던
  `5분 내 dbPoolTimeouts = 64`가
  실제로 재현 가능한 startup burst인지 따로 확인하기 위한 도구다.

### 측정 결과

- broad mixed, `warmup = 30s`:
  [soak-20260326-234830.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-234830.md)
  - `status = PASS`
  - `dbPoolTimeouts = 0`
  - `maxThreadsAwaitingConnection = 198`
- `browse_search+relation_mixed`, `warmup = 30s`:
  [soak-20260326-235634.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-235634.md)
  - `status = PASS`
  - `dbPoolTimeouts = 0`
  - `maxThreadsAwaitingConnection = 182`
- broad mixed, `warmup = 2m`:
  [soak-20260327-000344.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-000344.md)
  - `status = PASS`
  - `dbPoolTimeouts = 0`
  - `maxThreadsAwaitingConnection = 193`

### 원인 분석

- 분리된 `5분` 런에서는 broad mixed도, 주요 pair profile도,
  원래와 같은 `2분 warmup` 조건에서도 timeout burst가 재현되지 않았다.
- 즉 [soak-20260326-194048.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048.md)
  의 `dbPoolTimeouts = 64`는
  현재 코드 기준 `항상 재현되는 startup 병목`이라고 보긴 어렵다.
- 오히려 현재 data는:
  - steady-state browse는 충분히 줄었고
  - search drift는 남아 있지만 short burst를 직접 만들진 않았으며
  - 문제는 full `2시간` long-run 안에서 우연히 겹친 contention noise일 수 있음을 가리킨다.

### 교훈

- long-run에서 한번 나온 초기 timeout만으로
  곧바로 `재현 가능한 startup bottleneck`이라고 단정하면 안 된다.
- 같은 factor와 warmup 조건으로 짧게 분리해 보면,
  많은 경우 long-run 고유 노이즈와 실제 구조적 병목을 분리할 수 있다.
- 이 단계 덕분에 다음 우선순위는
  `초기 burst 전용 응급처치`가 아니라
  여전히 남아 있는 `post.list.search.rows` 최적화 쪽으로 돌아갔다.

## 2026-03-27 clean broad mixed `0.9 / 2시간` partial rerun

상태: 완료

### 실행 조건

- clean `effectivedisco_loadtest` DB `DROP/CREATE`
- fresh `loadtest` app
- `SOAK_FACTOR=0.9`
- `SOAK_DURATION=2h`
- `WARMUP_DURATION=2m`
- `SAMPLE_INTERVAL_SECONDS=300`

주의:

- 이번 런은 strict failure가 `15분` 시점에 이미 확정됐고,
  `70분`까지 같은 수치로 plateau가 유지돼
  runner를 `1시간 10분` 시점에서 중단했다.
- 따라서 full runner 산출 `.md`가 아니라
  수동 summary 파일로 결과를 정리했다.

### 결과

- summary:
  [soak-20260327-003415.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-003415.md)
- log:
  [soak-20260327-003415.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-003415.log)
- metrics:
  [soak-20260327-003415-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-003415-metrics.jsonl)

숫자:

- `status = FAIL (strict)`
- `actual_runtime = 1h 10m`
- `dbPoolTimeouts = 73`
- `duplicateKeyConflicts = 0`
- `unreadNotificationMismatchUsers = 0`
- `currentThreadsAwaitingConnection = 177`
- `maxThreadsAwaitingConnection = 200`

주요 profile:

- `post.list.browse.rows avgWall ≈ 1.78ms`
- `post.list.search.rows avgWall ≈ 14.77ms`
- `notification.store avgWall ≈ 2.25ms`
- `notification.read-page.summary.transition avgWall ≈ 3.07ms`

### 직전 clean `0.9 / 2시간` rerun과의 비교

- 비교 기준:
  [soak-20260326-194048.md](/home/admin0/effective-disco/loadtest/results/soak-20260326-194048.md)
  대비
  [soak-20260327-003415.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-003415.md)
- `dbPoolTimeouts = 64 -> 73`, `14.1% 악화`
- `post.list.browse.rows avgWall = 1.80ms -> 1.78ms`, `1.1% 개선`
- `post.list.search.rows avgWall = 20.37ms -> 14.77ms`, `27.5% 개선`
- `notification.store avgWall = 2.28ms -> 2.25ms`, `1.3% 개선`
- `notification.read-page.summary.transition avgWall = 3.09ms -> 3.07ms`, `0.6% 개선`

### 원인 분석

- strict failure는 다시 재현됐지만,
  steady-state read/write drift 자체는 직전 rerun보다 오히려 더 좋아졌다.
- 즉 현재 `0.9 / 2시간` gap은
  long-run 후반 saturation보다
  초반에 한 번 발생하는 timeout burst의 영향이 더 크다.
- 그와 동시에 steady-state 경로의 우선순위는 계속 `post.list.search.rows`다.

### 교훈

- `strict fail 재현`과 `steady-state 성능`은 분리해서 봐야 한다.
- 이번 partial rerun은 strict 기준에선 다시 실패였지만,
  steady-state 지표만 보면 search path를 제외한 나머지는 이미 충분히 안정적이었다.
- 따라서 다음 최적화는 broad browse나 notification이 아니라
  `post.list.search.rows`와 early burst 원인 분해에 집중하는 게 맞다.
