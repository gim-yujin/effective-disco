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

## 2026-03-27 `post.list.search.rows` `id-first` 최적화

상태: 구현 완료 / 검증 완료 / soak 재측정 미완료

### 배경

- clean `0.85 / 2시간`은 통과했지만
  clean `0.9 / 2시간` strict failure는 두 번 다시 재현됐다.
- 그 시점의 steady-state profile에서
  `post.list.browse.rows`, `notification.*`는 대부분 안정적이었고,
  가장 크게 남는 read drift는 `post.list.search.rows`였다.
- 즉 다음 타깃은 broad browse나 notification이 아니라
  search rows의 data flow 자체였다.

### 기존 문제

- board/global keyword search slice는
  검색 매칭과 row projection을 사실상 한 경로로 묶고 있었다.
- `PostService.getPostSlice()` 기준으로 보면
  hot API 계약은 이미 `Slice`였지만,
  search path는 현재 window를 먼저 가볍게 자르지 못한 채
  FTS/username matching 뒤 projection row를 바로 만들었다.
- 이 구조는 browse처럼 `id-first -> small row batch`로 줄일 수 있는 여지가 있는데도
  search만 상대적으로 무거운 row materialization을 유지하게 만들었다.

### 구현

- [PostRepositoryCustom.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryCustom.java)
  - search slice 반환 타입을 `Slice<PostListRow>`에서 `Slice<Long>`으로 바꿨다.
- [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  - PostgreSQL/H2 fallback keyword slice SQL을 모두 `id`만 반환하도록 변경했다.
  - board-scoped/global search 모두 현재 cursor window의 `post id`만 먼저 결정한다.
- [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - `findPostListRowsByIdIn(...)` projection batch query를 추가했다.
- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - `loadSearchSlice()`가 이제 `search ids -> small row batch` 두 단계로 동작한다.
  - board-scoped search는 기존 browse와 같은 `toBoardScopedSlice(...)`를 재사용하고,
    global search는 새 `toGlobalProjectionSlice(...)`로 작은 id 집합만 projection 한다.

### 계측 분해

- coarse profile은 유지했다.
  - `post.list.search.rows`
- 새 세부 profile을 추가했다.
  - `post.list.search.keyword.rows`
  - `post.list.search.tag.rows`
  - `post.list.search.sort.rows`

이렇게 해야 기존 문서의 추세를 깨지 않으면서도,
다음 long-run에서 `keyword/tag/sort` 중 어느 검색이 실제 hot path인지 분리할 수 있다.

### trade-off 판단

- statement 수는 search slice 기준으로 `id query + row batch query`로 늘어난다.
- 하지만 이 변경은 search hot path에서
  큰 row materialization과 projection join을 current window 이후까지 끌고 가는 비용을 줄인다.
- 즉 `상수 1회 쿼리 추가`와 맞바꿔
  long soak에서 커넥션 점유 시간을 낮추는 방향이다.
- controller/API/SSR 계약은 건드리지 않았기 때문에
  module dependency 부작용은 `repository -> service` 내부에 국한된다.

### 검증

- targeted:
  - [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java)
  - [PostControllerTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/controller/PostControllerTest.java)
- full:
  - `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon`
- 결과:
  - targeted 통과
  - full test 통과

### 보류 사항

- clean short soak를 바로 재측정하려 했지만,
  [run-bbs-soak.sh](/home/admin0/effective-disco/loadtest/run-bbs-soak.sh)의 readiness check가
  `localhost`/`127.0.0.1` 환경 차이 때문에 실패해
  이번 턴에는 soak 결과를 새 기준선으로 추가하지 않았다.
- 따라서 다음 실측 우선순위는
  이 search `id-first` 변경 기준으로 다시 `0.9 / 15분 -> 0.9 / 2시간`을 재보는 것이다.

## 2026-03-27 soak runner readiness 정규화 + clean `0.9 / 15분` 재측정

상태: 구현 완료 / 실측 완료

### runner 수정

- [run-bbs-soak.sh](/home/admin0/effective-disco/loadtest/run-bbs-soak.sh)
  - `BASE_URL=http://localhost:...` 입력을 내부적으로
    `127.0.0.1` loopback으로 정규화하도록 바꿨다.
  - readiness check, reset, metrics sampling, final metrics, cleanup가
    같은 effective endpoint를 쓰도록 맞췄다.
  - `문제 해결`의 목적은
    이 환경에서 `localhost`가 IPv6로 먼저 해석되고
    runner 내부 `curl`만 실패하는 현상을 제거하는 것이었다.
- [loadtest/README.md](/home/admin0/effective-disco/loadtest/README.md)
  - 위 동작을 사용법에 반영했다.

### 실행 결과

- suite:
  [soak-20260327-053923.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-053923.md)
- k6 summary:
  [soak-20260327-053923-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-053923-k6.json)
- timeline:
  [soak-20260327-053923-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-053923-metrics.jsonl)
- sql snapshot:
  [soak-20260327-053923-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260327-053923-sql.tsv)

숫자:

- `status = FAIL`
- `http p95 = 230.07ms`
- `http p99 = 293.39ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 4`
- `unreadNotificationMismatchUsers = 0`

주요 profile:

- `post.list.search.rows wall ≈ 5.11ms / sql ≈ 4.09ms`
- `post.list.search.keyword.rows wall ≈ 5.10ms / sql ≈ 4.09ms`
- `post.list.search.tag.rows wall ≈ 1.45ms`
- `post.list.search.sort.rows wall ≈ 1.65ms`
- `post.list.browse.rows wall ≈ 1.67ms`
- `notification.store wall ≈ 2.09ms`
- `notification.read-page.summary.transition wall ≈ 2.89ms`

### 직전 동일 factor clean baseline 대비 비교

- 비교 기준:
  [soak-20260325-203507.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507.md)
  대비
  [soak-20260327-053923.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-053923.md)
- `http p95 = 246.53ms -> 230.07ms`, `6.7% 개선`
- `http p99 = 314.19ms -> 293.39ms`, `6.6% 개선`
- `post.list.browse.rows avgWall = 4.53ms -> 1.67ms`, `63.2% 개선`
- `post.list.browse.rows avgSql = 4.02ms -> 0.74ms`, `81.7% 개선`
- `post.list.search.rows avgWall = 3.76ms -> 5.11ms`, `35.7% 악화`
- `post.list.search.rows avgSql = 3.31ms -> 4.09ms`, `23.3% 악화`
- `notification.store avgWall = 2.53ms -> 2.09ms`, `17.7% 개선`
- `notification.store avgSql = 1.95ms -> 1.34ms`, `31.6% 개선`
- `notification.read-page.summary.transition avgWall = 2.93ms -> 2.89ms`, `1.1% 개선`
- `notification.read-page.summary.transition avgSql = 1.98ms -> 1.88ms`, `4.9% 개선`

### 원인 분석

- `browse` 경로는 search 변경의 간접 효과까지 포함해 크게 좋아졌다.
- `notification.store`와 `notification.read-page.summary.transition`도 소폭 개선됐다.
- 하지만 이번 변경의 직접 타깃이던 `post.list.search.rows`는
  `id-first -> small row batch`로 바꾼 뒤 오히려 느려졌다.
- data flow 관점에서 보면:
  - 이전에는 search rows가 `1 statement`로 끝났고
  - 지금은 `search id query + small row batch query`의 `2 statements` 구조다.
  - browse에서는 이 trade-off가 이득이었지만,
    현재 search hot path에선 `window 축소 이득`보다
    `statement 추가 + 재물질화 비용`이 더 컸다.

### 교훈

- `browse`에서 성공한 `id-first` 패턴이
  `search`에서도 그대로 통할 것이라고 가정하면 안 된다.
- 정렬 window가 지배적인 경로와
  검색 매칭 자체가 지배적인 경로는
  같은 모양의 최적화라도 trade-off가 다르다.
- 이번 케이스는
  `browse 63% 개선` 같은 국소 성공이 있어도,
  `search 35.7% 악화`와 strict `PASS -> FAIL`이 생기면
  전체 최적화로 채택하면 안 된다는 좋은 예다.

## 2026-03-27 `post.list.search.rows` `id-first` 실험 rollback

상태: 완료

### rollback 이유

- 위 실험은 의도와 달리
  `post.list.search.rows`를 느리게 만들었고,
  clean broad mixed `0.9 / 15분` strict 기준을
  `PASS -> FAIL`로 되돌렸다.
- data flow 관점에서 보면
  browse 와 달리 search 는
  `정렬 window 절감`보다 `statement 추가 + row 재물질화` 비용이 더 컸다.
- 즉 browse 에서 성공한 모양을 search 에 그대로 복제한 것이
  잘못된 trade-off 였다.

### rollback 범위

- search 관련 source 만 되돌렸다.
  - [PostRepositoryCustom.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryCustom.java)
  - [PostRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepository.java)
  - [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  - [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java)
- 유지한 변경:
  - ranked browse `id-first` 최적화
  - notification consistency / store 최적화
  - soak runner readiness 정규화

### 검증

- targeted:
  - `PostListOptimizationIntegrationTest`
  - `PostControllerTest`
- full:
  - `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon`
- 결과:
  - targeted 통과
  - full test 통과

### 현재 해석

- `search id-first` 실험 결과는
  최적화 설계 교훈으로는 남겨두되,
  현재 코드 baseline 으로는 사용하지 않는다.
- 다음 실측 우선순위는
  rollback 된 코드 기준으로
  `clean 0.9 / 15분 -> clean 0.9 / 2시간`을 다시 재는 것이다.

## 2026-03-27 rollback 이후 clean `0.9 / 15분` 재측정

상태: 실측 완료

### 결과

- suite:
  [soak-20260327-063924.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-063924.md)
- k6 summary:
  [soak-20260327-063924-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-063924-k6.json)
- server snapshot:
  [soak-20260327-063924-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-063924-server.json)
- timeline:
  [soak-20260327-063924-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-063924-metrics.jsonl)
- sql snapshot:
  [soak-20260327-063924-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260327-063924-sql.tsv)

숫자:

- `status = FAIL (strict)`
- `http p95 = 231.56ms`
- `http p99 = 293.50ms`
- `unexpected_response_rate = 0.0002`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`
- `unreadNotificationMismatchUsers = 0`

5분 구간 관측:

- `5분`: `post.list.search.rows avgWall ≈ 2.96ms`
- `10분`: `post.list.search.rows avgWall ≈ 3.79ms`
- `15분`: `post.list.search.rows avgWall ≈ 4.48ms`
- 전 구간 `dbPoolTimeouts = 0`

### rollback 효과

- rollback 전 실험 런:
  [soak-20260327-053923.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-053923.md)
- rollback 후 런:
  [soak-20260327-063924.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-063924.md)
- `dbPoolTimeouts = 4 -> 0`, `100% 개선`
- `unexpected_response_rate = 0.0000 -> 0.0002`, strict 기준으로는 악화
- `http p95 = 230.07ms -> 231.56ms`, `0.6% 악화`
- `http p99 = 293.39ms -> 293.50ms`, `0.0% 수준의 사실상 동일`

### 해석

- search `id-first` rollback 은 목적대로
  `dbPoolTimeouts` 회귀를 제거했다.
- 하지만 clean `0.9 / 15분` strict 기준은 아직 완전 통과가 아니다.
- 남은 failure 는 `unexpected_response_rate = 0.0002`이며,
  성격상 pool saturation 보다는 소량의 endpoint/check noise 에 가깝다.
- 이 차이는 중요하다.
  - 이전 failure 는 `search rows` 구조 변경이 직접 만든
    `connection contention`이었다.
  - 현재 failure 는 strict runner 가
    `unexpected_response_rate != 0.0000`을 허용하지 않기 때문에 발생한다.

### 교훈

- 잘못된 최적화는 빨리 rollback 해서 기준선을 복원해야 한다.
- strict soak 판정은
  `성능 문제`와 `운영 정책상 zero-tolerance`를 구분해서 읽어야 한다.
- 현재 search path 의 다음 최적화는
  browse 의 `id-first`를 복제하는 방식이 아니라,
  실제 unexpected response 원인이나
  keyword search matching cost 를 좁히는 방식으로 가야 한다.

## 2026-03-27 clean `0.9 / 15분` `dbPoolTimeouts=3` 재현성 확인

상태: 실측 완료

### 결과

- manual summary:
  [soak-20260327-081109.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-081109.md)
- k6 summary:
  [soak-20260327-081109-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-081109-k6.json)
- metrics timeline:
  [soak-20260327-081109-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-081109-metrics.jsonl)
- log:
  [soak-20260327-081109.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-081109.log)

숫자:

- `status = PASS (manual summary)`
- `http p95 = 252.34ms`
- `http p99 = 321.57ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`
- `unreadNotificationMismatchUsers = 0`

5분 구간 관측:

- `5분`: `post.list.search.rows avgWall ≈ 3.18ms`
- `10분`: `post.list.search.rows avgWall ≈ 4.13ms`
- `15분`: `post.list.search.rows avgWall ≈ 4.93ms`
- 전 구간 `dbPoolTimeouts = 0`, `Request Failed = 0`

### 해석

- 직전 [soak-20260327-074551.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-074551.md)
  의 `dbPoolTimeouts = 3` 은 같은 조건에서 재현되지 않았다.
- 즉 현재 strict `0.9 / 15분` gap 을
  `재현성 있는 pool saturation`으로 바로 확정할 단계는 아니다.
- 남은 steady-state hot path는 여전히 `post.list.search.rows` 이지만,
  이번 재측정 결과만 보면 `3건 timeout`은 search 구조의 확정적 한계보다는
  런 노이즈에 더 가깝다.

### 운영상 교훈

- strict soak gap 을 한 번의 런으로 확정하면 안 된다.
- `dbPoolTimeouts = 3 -> 0`처럼 작은 절대값은
  같은 clean 조건에서 최소 1회 이상 재현성을 확인해야 해석할 수 있다.
- 동시에 runner 후처리 안정성도 별도 문제다.
  이번 런은 main phase 는 정상 종료했지만,
  후처리 hang 때문에 final SQL snapshot 과 server summary 는 수동으로 보완했다.

## 2026-03-27 clean `0.9 / 2시간` managed rerun with `30초` sampling

상태: 실측 완료

### 결과

- manual summary:
  [soak-20260327-085407.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-085407.md)
- k6 summary:
  [soak-20260327-085407-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-085407-k6.json)
- metrics timeline:
  [soak-20260327-085407-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-085407-metrics.jsonl)
- log:
  [soak-20260327-085407.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-085407.log)

숫자:

- `status = FAIL (partial, manual summary)`
- `http p95 = 236.74ms`
- `http p99 = 300.33ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 1`
- `duplicateKeyConflicts = 0`
- `unreadNotificationMismatchUsers = 0`

30초 구간 관측:

- `08:54:37`: `dbPoolTimeouts = 1`, `search.rows ≈ 2.04ms`
- `08:55:07`: `dbPoolTimeouts = 1`, `search.rows ≈ 2.57ms`
- `08:55:37`: `dbPoolTimeouts = 1`, `search.rows ≈ 3.25ms`
- `09:04:44`: `dbPoolTimeouts = 1`, `search.rows ≈ 3.84ms`
- `09:05:15`: `dbPoolTimeouts = 1`, `search.rows ≈ 3.92ms`

### 해석

- `0.9 / 2시간` strict failure 는 현재 코드에서도 다시 재현됐다.
- 하지만 이번 재현은 `380건`, `73건`, `64건` 같은 누적형이 아니라
  `30초 시점 1건 발생 후 더 늘지 않는 plateau` 형태다.
- 따라서 지금 남은 문제는
  `browse`나 `notification`의 장시간 drift 보다
  `초기 connection contention burst`에 더 가깝다.
- steady-state path 중에서는 여전히 `post.list.search.rows`가 가장 큰 read path다.

### 운영상 교훈

- `0.9 / 2시간`은 현재 strict 기준에서 아직 `PASS`라고 부를 수 없다.
- 다만 실패 강도는 과거보다 크게 낮아졌고,
  현재 운영형 baseline을 `0.85 / 2시간`으로 유지하는 근거가 더 선명해졌다.
- 다음 최적화는 broad `read path` 전체가 아니라
  `초기 burst`와 `search.rows`를 분리해서 다뤄야 한다.

## 2026-03-27 notification/user lock burst substep profiling

### 배경

- clean `0.9 / 2시간` managed rerun
  [soak-20260327-085407.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-085407.md)
  에서 strict fail 은 `dbPoolTimeouts = 1` 한 건이
  `30초` 시점에 바로 발생한 뒤 plateau 하는 형태였다.
- 이 시점의 해석 후보는 둘이었다.
  - `notification` 경로의 recipient lock convoy
  - `post.list.search.rows` 같은 steady-state read pressure
- 기존 profile 은 `notification.store`, `notification.read-page.summary.transition`
  수준까지만 있어서, 실제로 잠금 획득이 두꺼운지 직접 보이지 않았다.

### 구현

- [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java)
  에 아래 substep profile 을 추가했다.
  - `notification.store.lock-recipient`
  - `notification.store.insert`
  - `notification.store.counter.increment`
  - `notification.read-page.lock-recipient`
  - `notification.read-page.page-state`
  - `notification.read-page.mark-ids`
  - `notification.read-page.counter.refresh`
  - `notification.read-all.lock-recipient`
  - `notification.read-all.find-cutoff`
  - `notification.read-all.mark-cutoff`
  - `notification.read-all.counter.refresh`
- 목적은 `notification` 경로를 behavior 변경 없이
  `lock -> row transition -> counter sync` 단계로 분해하는 것이다.

### 검증

- targeted test:
  - `NotificationServiceTest`
  - `NotificationAfterCommitIntegrationTest`
- command:
```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon --tests "com.effectivedisco.service.NotificationServiceTest" --tests "com.effectivedisco.service.NotificationAfterCommitIntegrationTest"
```
- 결과: 통과

### 측정 결과

- summary:
  [soak-20260327-100853.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-100853.md)
- server metrics:
  [soak-20260327-100853-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-100853-server.json)
- timeline:
  [soak-20260327-100853-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-100853-metrics.jsonl)
- 상태: `PASS`
- `http p95 = 225.38ms`
- `http p99 = 287.17ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `maxThreadsAwaitingConnection = 198`

핵심 substep:

- `notification.store.lock-recipient`
  - `avgWall ≈ 1.018ms`
  - `avgSql ≈ 0.813ms`
  - `maxWall ≈ 29.711ms`
- `notification.store.insert`
  - `avgWall ≈ 0.523ms`
- `notification.store.counter.increment`
  - `avgWall ≈ 0.411ms`
- `notification.read-page.lock-recipient`
  - `avgWall ≈ 1.011ms`
  - `avgSql ≈ 0.854ms`
  - `maxWall ≈ 14.975ms`
- `notification.read-page.page-state`
  - `avgWall ≈ 0.445ms`
- `notification.read-page.mark-ids`
  - `avgWall ≈ 0.786ms`
- `notification.read-page.counter.refresh`
  - `avgWall ≈ 0.625ms`
- 비교용
  - `post.list.search.rows avgWall ≈ 2.871ms`
  - `post.list.browse.rows avgWall ≈ 1.691ms`

### 해석

- `notification/user lock`은 실제 burst 참여자다.
- 다만 `lock-recipient` 평균이 `1ms`대이므로
  현재 문제를 `단일 느린 lock query`로 설명하기는 어렵다.
- 대신 `maxWall 15~30ms` 스파이크와 높은 `awaiting` 값이 같이 보였으므로,
  현재 그림은 `짧은 recipient lock convoy`가 반복해서 겹치는 쪽에 더 가깝다.
- 동시에 steady-state hot path는 여전히 `post.list.search.rows`다.
- 즉 남은 strict gap 은
  `notification lock만의 문제`도,
  `search query 하나만의 문제`도 아니고,
  `짧은 lock 경쟁 + search read pressure + pool headroom 부족`
  의 조합으로 읽는 것이 가장 맞다.

### 교훈

- broad mixed burst 를 추적할 때는 endpoint 단위 profile 만으로는 부족하다.
- `notification.store`처럼 하나의 이름 아래 여러 DB step 이 숨어 있으면
  lock 획득 단계와 실제 insert/update 단계를 분리해서 봐야 한다.
- 이번 계측으로 다음 우선순위가 더 선명해졌다.
  - `notification`은 lock convoy 참여자
  - `search rows`는 steady-state headroom 소비자
  - 따라서 다음 최적화는 둘 중 하나만 보는 것이 아니라
    burst 와 steady-state 를 분리해서 다뤄야 한다.

## 2026-03-27 clean `0.9 / 2시간` managed partial rerun after burst instrumentation

### 배경

- 직전 clean managed rerun
  [soak-20260327-085407.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-085407.md)
  은 `30초` 시점 `dbPoolTimeouts = 1` 후 plateau 였다.
- 그 뒤 [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java)
  에 notification lock substep 계측을 추가했고,
  다음 질문은 단순했다.
  - `0.9 / 2시간`의 초기 burst가 정말 재현 가능한가?

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- `managed soak`
- `SOAK_FACTOR = 0.9`
- `SOAK_DURATION = 2h`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 30`
- 초기 burst 확인이 목적이라 `15분`에서 중단

### 결과

- manual summary:
  [soak-20260327-105907.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-105907.md)
- timeline:
  [soak-20260327-105907-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-105907-metrics.jsonl)
- log:
  [soak-20260327-105907.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-105907.log)

핵심:

- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`
- `maxThreadsAwaitingConnection = 194`

30초 샘플:

- `30초`: `search.rows ≈ 2.33ms`, `browse.rows ≈ 2.04ms`, `store.lock ≈ 1.35ms`
- `5분`: `search.rows ≈ 3.14ms`, `browse.rows ≈ 1.82ms`, `store.lock ≈ 1.12ms`
- `10분`: `search.rows ≈ 3.98ms`, `browse.rows ≈ 1.79ms`, `store.lock ≈ 1.11ms`
- `15분`: `search.rows ≈ 4.68ms`, `browse.rows ≈ 1.78ms`, `store.lock ≈ 1.12ms`

### 해석

- 이번 partial rerun에서는 `초기 30초 timeout 1건`이 전혀 재현되지 않았다.
- 따라서 현재 clean `0.9 / 2시간` gap을
  `초기 burst 재현 이슈`로 계속 해석하는 것은 맞지 않다.
- notification lock substep 은 여전히 존재하지만,
  적어도 이번 clean rerun에서는 `timeout trigger`로 동작하지 않았다.
- 지금 남는 steady-state 관심사는 여전히 `post.list.search.rows`다.

### 교훈

- `한 번 발생한 초기 burst`를 곧바로
  `재현성 있는 병목`으로 고정하면 안 된다.
- 동일 clean 조건에서 managed rerun 으로 다시 확인한 뒤에야
  `noise`와 `real bottleneck`을 나눌 수 있다.
- 현재 단계에서 더 타당한 운영 결론은 이렇다.
  - `0.85 / 2시간`은 확정 baseline
  - `0.9 / 2시간`은 여전히 미확정이지만,
    더 이상 `초기 burst 재현`이 핵심 쟁점은 아니다.

## 2026-03-27 clean `0.9 / 2시간` full managed rerun

### 배경

- 앞선 managed partial rerun
  [soak-20260327-105907.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-105907.md)
  에서는 `15분`까지 `dbPoolTimeouts = 0` 이었다.
- 그래서 다음 질문은 다시 단순해졌다.
  - full `2시간` main phase 를 끝까지 돌리면
    strict `0.9 / 2시간` gap 이 실제로 남는가?
  - 남는다면 `notification lock`이냐, `search rows drift`냐?

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- managed soak
- `SOAK_FACTOR = 0.9`
- `SOAK_DURATION = 2h`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 30`
- wrapper 는 결과 파일 생성 후 종료 정리 단계에서 멈춰 수동 중단

### 결과

- manual summary:
  [soak-20260327-112452.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-112452.md)
- k6 summary:
  [soak-20260327-112452-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-112452-k6.json)
- server metrics:
  [soak-20260327-112452-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-112452-server.json)
- timeline:
  [soak-20260327-112452-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-112452-metrics.jsonl)
- sql snapshot:
  [soak-20260327-112452-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260327-112452-sql.tsv)
- 상태: `FAIL`
- `http p95 = 273.52ms`
- `http p99 = 351.66ms`
- `dbPoolTimeouts = 137`
- `duplicateKeyConflicts = 0`
- SQL snapshot 전부 `0`

### 10분 간격 관찰

- `30초`: `dbPoolTimeouts = 31`, `search.rows ≈ 2.05ms`, `store.lock ≈ 1.24ms`
- `10분`: `dbPoolTimeouts = 31`, `search.rows ≈ 3.83ms`
- `20분`: `dbPoolTimeouts = 31`, `search.rows ≈ 5.26ms`
- `30분`: `dbPoolTimeouts = 31`, `search.rows ≈ 6.68ms`
- `40분`: `dbPoolTimeouts = 31`, `search.rows ≈ 8.15ms`
- `50분`: `dbPoolTimeouts = 31`, `search.rows ≈ 9.64ms`
- `60분`: `dbPoolTimeouts = 137`, `search.rows ≈ 11.14ms`
- `80분`: `dbPoolTimeouts = 137`, `search.rows ≈ 14.46ms`
- `100분`: `dbPoolTimeouts = 137`, `search.rows ≈ 17.89ms`
- `120분`: `dbPoolTimeouts = 137`, `search.rows ≈ 20.95ms`

최종 profile:

- `post.list.search.rows avgWall ≈ 20.95ms / avgSql ≈ 20.40ms`
- `post.list.browse.rows avgWall ≈ 1.86ms / avgSql ≈ 0.79ms`
- `notification.store avgWall ≈ 2.35ms / avgSql ≈ 1.51ms`
- `notification.store.lock-recipient avgWall ≈ 1.14ms`
- `notification.read-page.lock-recipient avgWall ≈ 1.19ms`

### 해석

- 이번 full run 에서는 `notification lock`보다 `search rows drift`가 더 지배적이었다.
- `notification.store.lock-recipient`와 `notification.read-page.lock-recipient`는
  burst 참여자이지만, 최종 평균과 장기 상승 폭 모두 `search.rows`보다 작았다.
- 따라서 현재 `0.9 / 2시간` strict fail 의 중심 해석은
  `search steady-state headroom 부족`이다.
- 이 시점의 운영형 결론은 그대로다.
  - `0.85 / 2시간`은 신뢰 가능한 stable baseline
  - `0.9 / 2시간`은 아직 strict 기준 `FAIL`

### 교훈

- burst 분석만으로 long-run 병목을 확정하면 안 된다.
- `초기 burst가 noise인지`를 확인한 뒤에는,
  다시 full run 으로 돌아가 steady-state hot path 를 봐야 한다.
- 이번 full managed rerun 은
  `notification lock`을 의심하던 가설을 약화시키고,
  `post.list.search.rows`를 다음 최적화 1순위로 확정하는 근거가 됐다.

## 2026-03-27 clean `0.9 / 15분` rerun after split search path profiling

### 배경

- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)에
  `post.list.search.keyword.*`, `post.list.search.tag.*`, `post.list.search.sort.*`
  세분화 계측을 추가했다.
- 목적은 간단했다.
  - `search.rows`를 그대로 두되
  - 실제 hot path 가 `keyword`, `tag`, `sort` 중 어디인지 먼저 확정한다.

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- `SOAK_FACTOR = 0.9`
- `SOAK_DURATION = 15m`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 300`
- `BASE_URL = http://127.0.0.1:18082`

### 결과

- manual summary:
  [soak-20260327-152955.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-152955.md)
- log:
  [soak-20260327-152955.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-152955.log)
- timeline:
  [soak-20260327-152955-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-152955-metrics.jsonl)
- 상태: `MAIN_PHASE_CLEAN_BUT_FINALIZATION_INCOMPLETE`
- `http p95 = 242.57ms`
- `http p99 = 308.47ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`

세분화 profile:

- `post.list.search.keyword.rows ≈ 4.59ms / sql ≈ 4.09ms`
- `post.list.search.keyword.board.rows ≈ 4.59ms / sql ≈ 4.09ms`
- `post.list.search.tag.rows ≈ 1.54ms / sql ≈ 1.18ms`
- `post.list.search.sort.rows ≈ 1.83ms / sql ≈ 0.80ms`
- `post.list.search.sort.likes.rows ≈ 1.82ms`
- `post.list.search.sort.comments.rows ≈ 1.84ms`
- `post.list.browse.rows ≈ 1.83ms / sql ≈ 0.81ms`
- `notification.store ≈ 2.28ms / sql ≈ 1.48ms`

### 해석

- `search` 내부에서 가장 큰 분기는 `keyword`였다.
- 특히 현재 loadtest 시나리오에선 `board-scoped keyword search`가 그대로 최댓값이었다.
- `tag`와 `sort`는 상대적으로 작았으므로,
  다음 최적화의 주타깃을 `keyword search row path`로 좁히는 것이 맞다.
- 즉 `search`를 막연히 다시 뒤집는 것이 아니라,
  [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  의 `board keyword search`를 직접 줄이는 쪽이 가장 효율적이다.

### 주의

- 이번 런은 `run-bbs-soak.sh`가 again 후처리에서 멈춰
  final `k6.json`, `server.json`, `sql.tsv`는 생성되지 않았다.
- 따라서 이 섹션의 판정은 main phase `log`와 종료 직전 live metrics snapshot 기준이다.
- 그래도 세분화 계측의 목적은 충분히 달성됐다.
  - `keyword`가 제일 큼
  - `tag/sort`는 1순위가 아님

## 2026-03-27 board-scoped keyword search row path 최적화

### 배경

- 직전 split profiling 결과에서
  `post.list.search.keyword.board.rows ≈ 4.59ms / sql ≈ 4.09ms`가
  `search` 내부 최댓값이었다.
- 반면 `tag`와 `sort`는 각각 `1ms`대였다.
- 즉 다음 최적화는 `search` 전체를 다시 뒤집는 것이 아니라,
  [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  의 board-scoped keyword row path만 좁게 줄이는 것이 맞았다.

### 원인

- 기존 board keyword path는 `matched_post_ids`를 만든 뒤
  결과 row를 materialize 할 때 `users` join을 전체 matched id 집합에 먼저 붙였다.
- board 검색은 이미 `boardId`, `boardName`, `boardSlug`를 알고 있는데도,
  현재 page/slice window보다 큰 matched set에 대해 projection join 비용을 먼저 지불했다.
- 이 구조는 broad mixed steady-state에서 커넥션 점유 시간을 늘린다.

### 적용한 변경

- `POSTGRES_BOARD_KEYWORD_SQL`을
  일반 keyword content SQL 재사용 대신
  board 전용 `createPostgresBoardKeywordContentSql()`로 분리했다.
- `POSTGRES_BOARD_KEYWORD_SLICE_SQL`도
  board 전용 `createPostgresBoardKeywordSliceSql()`로 분리했다.
- 두 SQL 모두
  `matched_post_ids -> ordered_post_window -> small row batch join`
  흐름으로 바꿨다.
- 즉 board keyword path에서는:
  - 먼저 `posts`만으로 현재 page/slice의 `id` window를 자르고
  - 그 작은 결과에만 `users` join을 적용하고
  - `boardName/boardSlug`는 parameter로 주입한다.
- 이 변경은 board-scoped PostgreSQL keyword path에만 적용했다.
  global keyword path, count path, tag/sort path, 서비스/컨트롤러 계약은 그대로 유지했다.

### 검증

회귀 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.service.PostListOptimizationIntegrationTest" \
  --tests "com.effectivedisco.controller.PostControllerTest"
```

전체 테스트:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon
```

둘 다 통과했다.

### clean `0.9 / 15분` 재측정

- manual summary:
  [soak-20260327-161354.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-161354.md)
- log:
  [soak-20260327-161354.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-161354.log)
- timeline:
  [soak-20260327-161354-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-161354-metrics.jsonl)
- 상태: `MAIN_PHASE_CLEAN_BUT_FINALIZATION_INCOMPLETE`
- `http p95 = 242.57ms`
- `http p99 = 308.47ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`

핵심 profile:

- `post.list.search.keyword.board.rows ≈ 3.86ms / sql ≈ 3.53ms`
- `post.list.search.keyword.rows ≈ 3.86ms / sql ≈ 3.53ms`
- `post.list.search.rows ≈ 3.87ms / sql ≈ 3.53ms`
- `post.list.search.tag.rows ≈ 1.46ms / sql ≈ 1.09ms`
- `post.list.search.sort.rows ≈ 1.80ms / sql ≈ 0.79ms`
- `post.list.browse.rows ≈ 1.80ms / sql ≈ 0.80ms`
- `notification.store ≈ 2.23ms / sql ≈ 1.43ms`

### 전후 비교

직전 split profiling soak 대비:

- `post.list.search.keyword.board.rows avgWall`: `4.59ms -> 3.86ms`, `15.9% 개선`
- `post.list.search.keyword.board.rows avgSql`: `4.09ms -> 3.53ms`, `13.7% 개선`
- `post.list.search.rows avgWall`: `4.60ms -> 3.87ms`, `15.9% 개선`
- `post.list.search.rows avgSql`: `4.09ms -> 3.53ms`, `13.7% 개선`

### 해석

- 이번 변경은 `search 전체`를 넓게 다시 건드린 것이 아니라,
  실제 hot path 하나만 줄인 narrow optimization이다.
- `tag`, `sort`, `browse`, `notification.store`에 큰 회귀를 만들지 않으면서
  목표 경로인 board keyword search를 줄였다.
- 즉 이전 broad search 구조 변경처럼
  일부 경로를 줄이려다 전체를 망가뜨린 최적화가 아니라,
  의도한 곳만 줄인 좋은 trade-off에 가깝다.

## 2026-03-27 clean `0.9 / 15분` rerun to confirm board keyword optimization

### 배경

- board-scoped keyword row-path 최적화 직후 clean `0.9 / 15분`에서
  `post.list.search.keyword.board.rows ≈ 3.86ms`까지 내려가는 좋은 샘플이 한 번 나왔다.
- 하지만 다음 판단은 "좋은 샘플이 1회 있었는가"가 아니라
  "같은 clean 조건에서 이 개선이 다시 재현되는가"여야 한다.

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- `SOAK_FACTOR = 0.9`
- `SOAK_DURATION = 15m`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 300`
- `BASE_URL = http://127.0.0.1:18082`

### 결과

- k6 summary:
  [soak-20260327-164929-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-164929-k6.json)
- log:
  [soak-20260327-164929.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-164929.log)
- 상태: `MAIN_PHASE_PASS_BUT_FINALIZATION_INCOMPLETE`
- `http p95 ≈ 242.91ms`
- `http p99 ≈ 309.44ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`

핵심 profile:

- `post.list.search.keyword.board.rows ≈ 4.52ms / sql ≈ 4.18ms`
- `post.list.search.keyword.rows ≈ 4.52ms / sql ≈ 4.18ms`
- `post.list.search.rows ≈ 4.52ms / sql ≈ 4.18ms`
- `post.list.search.tag.rows ≈ 1.52ms / sql ≈ 1.15ms`
- `post.list.search.sort.rows ≈ 1.73ms / sql ≈ 0.72ms`
- `post.list.browse.rows ≈ 1.74ms / sql ≈ 0.73ms`
- `notification.store ≈ 2.24ms / sql ≈ 1.44ms`

### 해석

- main phase 기준 clean `0.9 / 15분`은 다시 깨끗하게 통과했다.
- 그러나 직전 좋은 샘플에서 보였던
  `post.list.search.keyword.board.rows ≈ 3.86ms`
  개선폭은 이번엔 강하게 재현되지 않았다.
- 즉 이 최적화는 최소한 short-run strict 기준을 해치지 않는 안전한 narrow change이긴 하지만,
  `board keyword rows`를 안정적으로 한 단계 더 낮췄다고 결론내리긴 아직 이르다.
- 따라서 다음 우선순위는 다시 `0.9 / 2시간` long-run 측정으로 돌아가,
  이 수준의 short-run headroom이 실제 장시간 기준선에도 도움이 되는지 확인하는 것이다.

### 교훈

- hot path를 좁게 줄인 최적화라도,
  단일 좋은 샘플만 보고 개선이 확정됐다고 판단하면 안 된다.
- short-run 재현성 확인을 한 번 더 넣어야
  `실제 개선`과 `좋은 측정 노이즈`를 구분할 수 있다.

## 2026-03-27 full clean managed `0.9 / 2시간` rerun

### 배경

- short-run `0.9 / 15분`은 다시 깨끗하게 통과했다.
- 이제 남은 질문은 단 하나였다.
  - 이 상태로 clean `0.9 / 2시간`이 실제로 버티는가?
- 그래서 이번에는 `run-bbs-managed-soak.sh`로 앱 lifecycle까지 wrapper가 관리하는 조건에서
  full `2시간`을 끝까지 다시 돌렸다.

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- `SOAK_FACTOR = 0.9`
- `SOAK_DURATION = 2h`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 600`
- `BASE_URL = http://127.0.0.1:18082`
- 실행 방식: `run-bbs-managed-soak.sh`

### 결과

- manual summary:
  [soak-20260327-172723.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-172723.md)
- log:
  [soak-20260327-172723.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-172723.log)
- timeline:
  [soak-20260327-172723-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-172723-metrics.jsonl)
- 상태: `MAIN_PHASE_PASS_BUT_FINALIZATION_INCOMPLETE`
- `http p95 = 263.59ms`
- `http p99 = 338.11ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`

10분 간격 상태:

- `10분`: `dbPoolTimeouts = 0`, `search.rows ≈ 2.25ms`
- `30분`: `dbPoolTimeouts = 0`, `search.rows ≈ 2.26ms`
- `60분`: `dbPoolTimeouts = 0`, `search.rows ≈ 2.28ms`
- `90분`: `dbPoolTimeouts = 0`, `search.rows ≈ 2.31ms`
- `120분`: `dbPoolTimeouts = 0`, `search.rows ≈ 2.33ms`

종료 직전 live metrics:

- `post.list.search.keyword.board.rows ≈ 20.47ms / sql ≈ 20.12ms`
- `post.list.search.rows ≈ 20.47ms / sql ≈ 20.12ms`
- `post.list.browse.rows ≈ 1.85ms / sql ≈ 0.78ms`
- `notification.store ≈ 2.33ms / sql ≈ 1.49ms`
- `notification.store.lock-recipient ≈ 1.13ms / sql ≈ 0.90ms`
- `notification.read-page.lock-recipient ≈ 1.16ms / sql ≈ 0.98ms`

### 해석

- 이번 full rerun에서 `0.9 / 2시간` main phase는 timeout 없이 끝까지 버텼다.
- `browse`와 `notification`은 장시간에도 더 이상 주병목으로 보이지 않았다.
- steady-state에서 가장 크게 남는 건 여전히 `post.list.search.keyword.board.rows`였다.
- 즉 이번 단계의 결론은:
  - `0.9 / 2시간` headroom은 실제로 크게 회복됐다
  - 남은 read hot path는 `board-scoped keyword search`
  - 다음 최적화도 `search keyword`를 벗어나 넓게 퍼지면 안 된다

### 주의

- wrapper 후처리 hang 때문에 final `server.json` / `sql.tsv` / 자동 summary는 생성되지 않았다.
- 따라서 이번 판정은 `k6 log`와 종료 직전 live metrics snapshot 기준이다.

### 교훈

- 장시간 soak는 단순히 `dbPoolTimeouts` 유무만 보는 것이 아니라
  `어떤 경로가 steady-state에서 끝까지 가장 크게 남는가`를 같이 봐야 한다.
- 이번 full run에서는 `search keyword`가 끝까지 최댓값이었고,
  그 덕분에 다음 최적화 대상이 다시 명확해졌다.

## 2026-03-27 soak runner 후처리 안정화

### 배경

- 최근 여러 soak에서 main phase 자체는 끝났는데도
  `run-bbs-soak.sh`가 final `server.json`, `sql.tsv`, summary `.md`
  생성 전에 오래 멈추는 경우가 반복됐다.
- 이 상태에선 실제 측정은 끝났는데도 사용자가 wrapper를 수동 중단하게 되고,
  결과적으로 final artifact가 비어 측정 기록이 불완전해졌다.
- data flow를 다시 따라가 보니 핵심 지점은
  `k6 종료 -> sampler wait -> final metrics/sql/cleanup`
  순서였다.
- 기존 구현은 `sample_metrics_loop()`가 `SAMPLE_INTERVAL_SECONDS` 동안 sleep 중일 때
  `k6`가 이미 끝나도 `wait "$sampler_pid"`가 그 sleep이 끝날 때까지 그대로 막혔다.

### 원인

- sampler는 `while kill -0 "$k6_pid"` 루프 내부에서 `sleep "$SAMPLE_INTERVAL_SECONDS"`를 돈다.
- 따라서 `k6`가 끝난 직후 sampler가 이미 sleep에 들어가 있으면,
  runner는 finalization으로 넘어가기 전에 sample interval만큼 추가 대기한다.
- 게다가 final 단계의 `curl metrics`, `psql sql snapshot`, `cleanup` 호출에도
  별도 timeout이 없어서, 이 단계가 길어지면 전체 suite가 다시 hang처럼 보였다.

### 적용한 변경

- [run-bbs-soak.sh](/home/admin0/effective-disco/loadtest/run-bbs-soak.sh)
  에 `capture_metrics_snapshot()`, `run_sql_snapshot()`, `cleanup_loadtest_scope()` helper를 추가했다.
- `k6` 종료 직후에는
  `kill "$sampler_pid"` 후 `wait "$sampler_pid"`로 sampler를 즉시 정리하도록 바꿨다.
- final metrics 수집에는 `curl --max-time 15` + retry를 넣었다.
- SQL snapshot에는 `PGOPTIONS='-c statement_timeout=30000'`를 적용했다.
- cleanup endpoint 호출에도 `--max-time 30`과 retry를 넣었다.

### 기대 효과

- main phase 종료 후 sample interval만큼 불필요하게 대기하지 않는다.
- final metrics, SQL snapshot, cleanup이 길어져도 빠르게 실패/성공이 드러난다.
- 사용자가 wrapper를 수동으로 끊기 전에 final artifact가 생성될 가능성이 높아진다.

### 검증

- `bash -n loadtest/run-bbs-soak.sh` 통과
- 짧은 soak smoke로 finalization을 바로 검증하려 했지만,
  현재 sandbox에서는 readiness가 간헐적으로 흔들려 end-to-end smoke는 결정적으로 마무리하지 못했다.
- 따라서 이 변경의 최종 검증은 다음 clean managed soak 재실행에서
  `server.json`, `sql.tsv`, `.md`가 자동 생성되는지로 확정하는 것이 맞다.

## 2026-03-27 soak runner cleanup/summary curl timeout 추가 완화

### 배경

- runner finalization 안정화 후에도 마지막 단계에서 간헐적으로 `.md` summary 만 빠지는 경우가 남았다.
- core artifact (`k6.json`, `server.json`, `sql.tsv`, `timeline`) 는 이미 생성됐는데,
  마지막 `/internal/load-test/cleanup` curl timeout 하나 때문에 summary 생성이 끝까지 가지 못하는 경우가 있었다.

### 원인

- [run-bbs-soak.sh](/home/admin0/effective-disco/loadtest/run-bbs-soak.sh) 는
  final metrics 와 SQL snapshot 을 남긴 뒤 cleanup endpoint 를 호출했다.
- 그런데 cleanup 호출이 timeout 되면 `set -e` 때문에 script 전체가 거기서 멈추고,
  실제 측정 결과가 이미 다 있는데도 마지막 `.md` summary 가 생성되지 못했다.
- 즉 cleanup 은 본질적으로 `후속 환경 위생` 단계인데,
  기존 data flow 에서는 `측정 결과 기록`보다 더 강한 blocker 로 취급되고 있었다.

### 적용한 변경

- cleanup timeout 을 `CLEANUP_CURL_MAX_TIME` env 로 분리하고 기본값을 `120s`로 늘렸다.
- cleanup 호출은 이제 best-effort 로 다룬다.
  - timeout 또는 non-2xx 가 나더라도 summary 생성은 계속 진행한다.
- summary table 에 `cleanupStatus`, `cleanupNote` 를 추가해
  cleanup 성공/실패 여부를 artifact 안에서 바로 확인할 수 있게 했다.

### 기대 효과

- final metrics / SQL snapshot 이 확보된 뒤에는
  cleanup timeout 이 더 이상 `.md` summary 생성을 막지 않는다.
- 즉 이후 managed soak 는
  `측정 결과 기록`과 `후속 cleanup` 을 분리해서 해석할 수 있다.

### 검증

- `bash -n loadtest/run-bbs-soak.sh` 통과
- 다음 managed soak 에서
  `.md` summary 가 자동 생성되는지와 `cleanupStatus` 기록이 실제로 남는지 확인하면 된다.

## 2026-03-27 soak runner finalization smoke verification

### 배경

- runner 후처리 안정화 변경 후,
  실제로 `summary/server/sql/timeline`이 자동 생성되는지 먼저 확인할 필요가 있었다.
- 이 단계의 목적은 성능 개선이 아니라
  이후 long soak 판정 체계를 다시 신뢰 가능한 상태로 되돌리는 것이었다.

### 결과

- summary:
  [soak-20260327-195048.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-195048.md)
- k6 summary:
  [soak-20260327-195048-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-195048-k6.json)
- server metrics:
  [soak-20260327-195048-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-195048-server.json)
- timeline:
  [soak-20260327-195048-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-195048-metrics.jsonl)
- sql snapshot:
  [soak-20260327-195048-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260327-195048-sql.tsv)
- 상태: `PASS`
- `http p95 = 257.68ms`
- `http p99 = 386.78ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`
- `unreadNotificationMismatchUsers = 0`

### 해석

- smoke 기준으로는 runner finalization이 다시 정상 동작했다.
- 즉 앞서 보이던 smoke-level artifact 누락은
  runner 로직 자체보다 sandbox readiness 흔들림의 영향이 더 컸다.
- 이후 long managed soak는 다시
  자동 생성 artifact 기준으로 해석해도 되는 상태가 됐다.

## 2026-03-27 clean `0.9 / 2시간` full managed rerun with core artifacts

### 배경

- runner finalization smoke 검증이 통과한 뒤,
  clean `0.9 / 2시간`을 다시 full managed soak로 수행했다.
- 이 단계의 목적은
  `0.9 / 2시간`을 운영형 baseline으로 실제로 올릴 수 있는지,
  그리고 core artifact 기준으로도 그 결론이 유지되는지 확인하는 것이었다.

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- managed soak
- `SOAK_FACTOR = 0.9`
- `SOAK_DURATION = 2h`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 600`

### 결과

- manual summary:
  [soak-20260327-195440.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440.md)
- k6 summary:
  [soak-20260327-195440-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440-k6.json)
- server metrics:
  [soak-20260327-195440-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440-server.json)
- timeline:
  [soak-20260327-195440-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440-metrics.jsonl)
- sql snapshot:
  [soak-20260327-195440-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440-sql.tsv)
- log:
  [soak-20260327-195440.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440.log)
- 상태: `PASS`
- `http p95 = 262.40ms`
- `http p99 = 334.75ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 0`
- SQL snapshot 전부 `0`
- `maxThreadsAwaitingConnection = 196`

종료 직전 profile:

- `post.list.search.keyword.board.rows ≈ 20.54ms / sql ≈ 20.20ms`
- `post.list.search.rows ≈ 20.55ms / sql ≈ 20.21ms`
- `post.list.browse.rows ≈ 1.82ms / sql ≈ 0.76ms`
- `notification.store ≈ 2.31ms / sql ≈ 1.46ms`

### 해석

- 이번 full rerun으로 `0.9 / 2시간`은 실제 `PASS` 결과를 가진 long-run baseline 후보가 아니라,
  그대로 운영형 baseline으로 승격 가능한 값이 됐다.
- `browse`, `notification`, duplicate-key, unread mismatch는
  더 이상 장시간 strict 판정의 blocker가 아니었다.
- 가장 크게 남는 steady-state path는 여전히 `post.list.search.keyword.board.rows`였지만,
  현재 수준에서는 strict failure를 만들지 않았다.
- 즉 다음 단계는 baseline 확보가 아니라,
  이 기준선 위에서 얼마나 더 headroom을 밀 수 있는지 확인하는 것이다.

### 교훈

- runner finalization이 안정화되면,
  이전의 `main phase는 좋아 보였지만 artifact가 불완전한 런`과
  `실제로 baseline으로 승격 가능한 런`을 명확히 구분할 수 있다.
- 이번 결과는 `search keyword`가 여전히 최댓값이라는 사실과,
  그 경로가 현재 baseline을 막지 않는다는 사실을 동시에 보여준다.
- 즉 이제 최적화 논의의 중심은 `기준선 확보`가 아니라 `headroom 확보`로 옮겨간다.

### 주의

- 이번 long managed run에서는 `k6.json`, `server.json`, `sql.tsv`, `timeline`, `log`까지는 모두 생성됐다.
- 다만 마지막 curl 기반 summary 단계가 한 번 timeout 나서,
  `.md`는 수동 요약 파일로 보완했다.

## 2026-03-27 clean `0.95 / 2시간` full managed soak

### 배경

- clean `0.9 / 2시간`이 운영형 baseline 으로 올라간 뒤,
  다음 질문은 단순해졌다.
  - `0.95 / 2시간`도 버티는가?
  - 아니라면 남는 headroom gap 은 어디서 터지는가?
- 동시에 이번 런은
  방금 바꾼 cleanup non-blocking finalization 이 실제 long soak 에서도
  `.md` summary 자동 생성까지 끝내는지 검증하는 자리이기도 했다.

### 실행 조건

- clean `effectivedisco_loadtest` DB 재생성
- managed soak
- `SOAK_FACTOR = 0.95`
- `SOAK_DURATION = 2h`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 600`

### 결과

- summary:
  [soak-20260327-232958.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958.md)
- k6 summary:
  [soak-20260327-232958-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958-k6.json)
- server metrics:
  [soak-20260327-232958-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958-server.json)
- timeline:
  [soak-20260327-232958-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958-metrics.jsonl)
- sql snapshot:
  [soak-20260327-232958-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958-sql.tsv)
- log:
  [soak-20260327-232958.log](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958.log)
- 상태: `FAIL`
- `http p95 = 274.74ms`
- `http p99 = 347.57ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 69`
- `duplicateKeyConflicts = 0`
- SQL snapshot 전부 `0`
- `cleanupStatus = failed`

### 10분 간격 관찰

- `10분`: `dbPoolTimeouts = 0`, `search.rows ≈ 3.72ms`
- `30분`: `dbPoolTimeouts = 0`, `search.rows ≈ 6.87ms`
- `50분`: `dbPoolTimeouts = 0`, `search.rows ≈ 10.05ms`
- `70분`: `dbPoolTimeouts = 0`, `search.rows ≈ 13.28ms`
- `90분`: `dbPoolTimeouts = 0`, `search.rows ≈ 16.71ms`
- `100분`: `dbPoolTimeouts = 69`, `search.rows ≈ 18.50ms`
- `110분`: `dbPoolTimeouts = 69`, `search.rows ≈ 20.26ms`
- `종료`: `dbPoolTimeouts = 69`

최종 profile:

- `post.list.search.keyword.board.rows ≈ 20.25ms / sql ≈ 19.91ms`
- `post.list.search.rows ≈ 20.26ms / sql ≈ 19.91ms`
- `post.list.browse.rows ≈ 1.81ms / sql ≈ 0.77ms`
- `notification.store ≈ 2.29ms / sql ≈ 1.46ms`

### 해석

- `0.95 / 2시간`은 현재 strict 기준 stable factor 가 아니다.
- 실패는 `notification`, `browse`, duplicate-key, unread drift 가 아니라
  다시 `search keyword steady-state headroom` 쪽에서 발생했다.
- timeout 은 초반 burst 형태가 아니라,
  `search.rows`가 `18ms+` 구간으로 들어간 뒤 `100분` 시점에 한 번 묶여서 생기고 plateau 했다.
- 따라서 현재 headroom ceiling 은 `0.9`와 `0.95` 사이로 보는 것이 맞다.

### 교훈

- baseline 을 올린 뒤엔 무턱대고 다음 배수를 보는 것이 아니라,
  `어느 시점부터 steady-state 경로가 timeout 묶음으로 바뀌는지`를 같이 봐야 한다.
- 이번 런은 `0.9`까지는 허용되는 `search keyword` 드리프트가,
  `0.95`에선 더 이상 허용되지 않는다는 걸 보여준다.
- 즉 다음 최적화는 다시 넓게 퍼지는 게 아니라,
  `board-scoped keyword search`에 집중하는 것이 맞다.

### 추가 검증

- 이번 런은 cleanup curl 이 timeout/409 로 끝났지만,
  `[soak-20260327-232958.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958.md)` 가 자동 생성됐다.
- 따라서 cleanup non-blocking finalization 변경은 실제 long soak 에서도 목적대로 동작했다.

## 2026-03-28 board-scoped keyword search EXPLAIN 기반 병목 확정 및 좁은 최적화

상태: 완료

### 배경

- clean `0.95 / 2시간` full managed soak 기준 남는 지배 경로는
  `post.list.search.keyword.board.rows`였다.
- 하지만 이 경로는 이미 한 차례 좁게 줄인 적이 있었고,
  다음 단계에서는 감으로 다시 뒤집지 않고
  `EXPLAIN (ANALYZE, BUFFERS)`로 실제 비싼 노드를 먼저 확정해야 했다.
- 목표는 search 전체가 아니라
  [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  의 `board-scoped keyword search` path 하나만 줄이는 것이었다.

### representative 데이터와 query shape 확인

- dedicated DB `effectivedisco_loadtest`에 대표 데이터셋을 따로 넣었다.
  - users: `300`
  - posts: `45,000`
  - board별 공개 게시물: `free/dev/qna` 각각 `15,000`
  - `free` board에서 FTS `load` match row: `12,000`
- 현재 board keyword SQL shape는 대략 이 흐름이었다.
  - `matched_post_ids`
  - `ordered_post_window`
  - final `posts + users` row materialization

### EXPLAIN 결과

기존 shape:

- `matched_post_ids`에서 `12,000` candidate가 만들어진 뒤
  `ordered_post_window`가 다시 `posts p ON p.id = m.id`로 재조인했다.
- 대표 EXPLAIN 기준:
  - execution time `≈ 31.67ms`
  - shared buffer hit `≈ 37,067`
- 실제 비싼 노드는 단일 slow query 하나가 아니라
  `matched_post_ids -> posts 재조인 -> created_at 정렬` 단계였다.
- final row materialization은 page window `21`건만 처리해서 작았다.

비교한 좁은 대안 shape:

- branch가 `id`만이 아니라 `id, created_at`를 바로 내보내고
  `ordered_post_window`가 그 candidate row를 그대로 정렬/제한
- 동일 representative 데이터 기준:
  - execution time `≈ 12.99ms`
  - shared buffer hit `≈ 1,067`

### 적용한 변경

- [PostRepositoryImpl.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/PostRepositoryImpl.java)
  에 board-scoped keyword path 전용 row branch를 추가했다.
  - `POSTGRES_FTS_POST_ROW_BRANCH`
  - `POSTGRES_USERNAME_POST_ROW_BRANCH`
- `createPostgresBoardKeywordContentSql()`
  - `matched_post_ids`를 `matched_post_rows(id, created_at)`로 변경
  - `ordered_post_window`에서 candidate 전체에 대한 `posts` 재조인을 제거
- `createPostgresBoardKeywordSliceSql()`
  - 같은 방식으로 `cursor` path도 `matched_post_rows` 기반으로 정리
- global keyword search, count path, tag/sort path, service/controller 계약은 건드리지 않았다.

### 해석

- 이번 병목의 핵심은 `FTS 자체가 너무 느리다`가 아니었다.
- 실제 비용은
  `FTS/username match -> candidate 1만건대 생성 -> created_at 정렬을 위해 posts PK 재조회`
  에 있었다.
- 즉 board keyword path에서 `created_at`를 candidate 단계로 끌어올리는 것만으로
  중간 `posts` 재조인을 없앨 수 있었고,
  이게 가장 큰 비용 절감 포인트였다.
- 이 변경은 data flow를 `search 전체`가 아니라
  `board keyword ordered window` 하나에만 국한했기 때문에
  module dependency 측면에서도 부작용 범위가 작다.

### 검증

- targeted:
  - `PostListOptimizationIntegrationTest`
  - `PostControllerTest`
- full:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon
```

결과:

- targeted 테스트 통과
- 전체 테스트 통과
- 아직 soak 재측정은 하지 않았다.
  다음 비교는 clean `0.95 / 15분` -> clean `0.95 / 2시간` 순서로 하는 것이 맞다.

### 주의

- EXPLAIN용 representative row `exp0328_*`는 dedicated DB에 직접 넣었다.
- 따라서 다음 soak 전에 `./loadtest/create-loadtest-db.sh`로 DB를 다시 만드는 것이 맞다.

## 2026-03-28 clean `0.95 / 15분` rerun after board keyword EXPLAIN optimization

상태: 완료

### 배경

- board-scoped keyword search path를 `EXPLAIN (ANALYZE, BUFFERS)` 기반으로 좁게 줄인 뒤,
  이 변경이 실제 soak에서도 headroom을 올리는지 바로 확인할 필요가 있었다.
- 목표는 simple하다.
  - `post.list.search.keyword.board.rows`가 실제로 내려갔는가?
  - 그 결과 strict `0.95 / 15분`이 통과하는가?

### 실행 조건

- dedicated `effectivedisco_loadtest` DB를 `DROP/CREATE`로 clean 상태로 재생성
- `SOAK_FACTOR = 0.95`
- `SOAK_DURATION = 15m`
- `WARMUP_DURATION = 2m`
- `SAMPLE_INTERVAL_SECONDS = 300`

### 결과

- summary:
  [soak-20260328-033432.md](/home/admin0/effective-disco/loadtest/results/soak-20260328-033432.md)
- k6 summary:
  [soak-20260328-033432-k6.json](/home/admin0/effective-disco/loadtest/results/soak-20260328-033432-k6.json)
- server metrics:
  [soak-20260328-033432-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260328-033432-server.json)
- timeline:
  [soak-20260328-033432-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260328-033432-metrics.jsonl)
- sql snapshot:
  [soak-20260328-033432-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260328-033432-sql.tsv)
- log:
  [soak-20260328-033432.log](/home/admin0/effective-disco/loadtest/results/soak-20260328-033432.log)
- 상태: `FAIL`
- `http p95 = 240.01ms`
- `http p99 = 302.59ms`
- `unexpected_response_rate = 0.0001`
- `dbPoolTimeouts = 132`
- `duplicateKeyConflicts = 0`
- `unreadNotificationMismatchUsers = 0`
- SQL snapshot 전부 `0`

종료 직전 profile:

- `post.list.search.keyword.board.rows ≈ 2.06ms / sql ≈ 1.74ms`
- `post.list.search.rows ≈ 2.06ms / sql ≈ 1.74ms`
- `post.list.browse.rows ≈ 1.73ms / sql ≈ 0.76ms`
- `notification.store ≈ 2.15ms / sql ≈ 1.38ms`
- `notification.store.lock-recipient ≈ 1.07ms / sql ≈ 0.85ms`
- `notification.read-page.lock-recipient ≈ 1.06ms / sql ≈ 0.89ms`
- `maxThreadsAwaitingConnection = 200`

### 해석

- 이번 변경은 목표 경로 자체에는 확실히 먹혔다.
  - `post.list.search.keyword.board.rows`는 이전 headroom failure 문맥의 `~20ms`대 장시간 값과 비교하면
    훨씬 낮은 `~2ms` 수준으로 내려왔다.
- 하지만 strict `0.95 / 15분`은 여전히 실패했다.
- 즉 이번 failure는 더 이상
  `board keyword search row path 하나가 느려서`라고 보기 어렵다.
- 현재 그림은 다음 조합에 가깝다.
  - `search`는 충분히 줄었지만
  - 짧은 recipient lock 경합과 write churn이 같이 붙으면서
  - pool `28`개를 빠르게 포화시키고
  - 그 결과 `dbPoolTimeouts=132`가 먼저 strict fail을 만든다

### 교훈

- `EXPLAIN` 기반 좁은 최적화는 맞았다.
  병목을 정확히 찌른 덕분에 `board keyword` 경로 자체는 유의미하게 줄었다.
- 그러나 `한 경로를 크게 줄였다고 strict suite 전체가 바로 통과하는 것은 아니다`.
  지금 단계에서는 `search path`보다 `lock/pool contention` 비중이 더 커졌다는 뜻이다.
- 즉 다음 최적화는 search를 다시 넓게 뒤집는 게 아니라,
  이제 상대적으로 더 남은 `notification/user lock convoy`와
  초기/중간 구간 pool saturation 쪽으로 초점을 옮겨야 한다.

## 2026-03-28 현재 결론과 중지 결정

상태: 운영형 baseline 확정, 성능 최적화 일시중지

### 현재 기준선

- 운영형 baseline:
  - clean managed `0.9 / 2시간 = PASS`
  - 대표 artifact:
    [soak-20260327-195440.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-195440.md)
- headroom ceiling:
  - clean managed `0.95 / 2시간 = FAIL`
  - 대표 artifact:
    [soak-20260327-232958.md](/home/admin0/effective-disco/loadtest/results/soak-20260327-232958.md)

### 읽기 성능 관점의 판단

- 현 시점에서 `browse/search`는 이미 충분히 강하다.
- 최신 narrow optimization 이후 strict failure 직전 profile에서도
  - `post.list.search.keyword.board.rows ≈ 2.06ms`
  - `post.list.search.rows ≈ 2.06ms`
  - `post.list.browse.rows ≈ 1.73ms`
  수준이었다.
- 즉 현재 read-heavy 관점에서는 `browse/search가 느려서 운영이 어렵다`고 보기 어렵다.

### 남은 이슈의 성격

- `0.95`를 막는 것은 더 이상 `browse/search 단일 query shape`가 아니다.
- 최신 결과 기준 남는 문제는
  `pool saturation + notification/user lock contention + write churn` 조합에 더 가깝다.
- 다시 말해, 추가 성능 작업을 계속하더라도
  다음 타깃은 `search`보다 `mixed write/lock convoy` 쪽이 된다.

### 결정

- 현재 프로젝트는
  `clean 0.9 / 2시간 = PASS`를 운영형 baseline으로 채택한다.
- 성능 최적화는 여기서 일시중지한다.
- 이유:
  - 읽기 경로 목표는 이미 달성했다.
  - 남은 개선은 headroom 확장 성격이며,
    추가 변경 대비 회귀 위험과 측정 비용이 더 커졌다.

### 재개 조건

- 성능 최적화를 다시 재개한다면 시작점은 다음이다.
  - `notification.read-page/store` recipient lock 경로
  - pool saturation이 실제로 재현되는 `0.95+` mixed soak
- 반대로 `browse/search`는 현 시점에서 추가 최적화 우선순위가 아니다.

## 2026-04-12 Notification lock 경로 최적화

상태: 완료 (soak 재검증 통과)

### 배경

- 2026-03-28 중지 결정 시 남은 병목은 `pool saturation + notification/user lock contention + write churn` 조합이었다.
- 직전 strict failure (clean `0.95 / 15분`) 프로파일:
  - `notification.store.lock-recipient ≈ 1.07ms / sql ≈ 0.85ms`
  - `notification.read-page.lock-recipient ≈ 1.06ms / sql ≈ 0.89ms`
  - `dbPoolTimeouts = 132`
  - `maxThreadsAwaitingConnection = 200`
- 세 개의 notification hot path (store / read-page / read-all)가 모두 같은 User 행에
  `SELECT ... FOR UPDATE` lock을 건다.
  같은 수신자에 대한 store/read 가 모두 직렬화되면서 lock convoy → pool saturation 발생.

### 원인 분석

| 경로 | Lock 방식 | Counter 갱신 |
|------|----------|-------------|
| `storeNotificationAfterCommit` | `findNotificationRecipientSnapshotByUsernameForUpdate` (native FOR UPDATE) | `incrementUnreadNotificationCount` (atomic) |
| `markNotificationPageRead` | `findByIdForUpdate` (PESSIMISTIC_WRITE) | `refreshUnreadNotificationCount` (COUNT subquery) |
| `markAllUnreadNotificationsRead` | `findByIdForUpdate` (PESSIMISTIC_WRITE) | `refreshUnreadNotificationCount` (COUNT subquery) |

lock 은 `unreadNotificationCount` 비정규화 카운터의 정합성을 보장하기 위해 존재했다.
그러나:
- `incrementUnreadNotificationCount`는 이미 atomic SQL (`SET count = count + 1`)
- `markPageAsReadByIds` / `markAllAsReadUpToId`는 `WHERE isRead = false` 조건으로 실제 전환 수를 정확히 반환
- 반환값을 `decrementUnreadNotificationCount(delta)` 의 delta로 사용하면 lock 없이도 counter drift 없음
- PostgreSQL UPDATE 자체의 row-level lock이 counter 갱신을 자연 직렬화

### 적용한 변경

| 경로 | 이전 | 이후 |
|------|------|------|
| store | `findNotificationRecipientSnapshotByUsernameForUpdate` (FOR UPDATE) | `findNotificationRecipientSnapshotByUsername` (lock 없음) |
| read-page | `findByIdForUpdate` + `refreshUnreadNotificationCount` (COUNT subquery) | lock 제거 + `decrementUnreadNotificationCount(delta)` |
| read-all | `findByIdForUpdate` + `refreshUnreadNotificationCount` (COUNT subquery) | lock 제거 + `decrementUnreadNotificationCount(delta)` |

- 변경 파일: `NotificationService.java`, `NotificationServiceTest.java`
- 동시성 통합 테스트 (`NotificationAfterCommitIntegrationTest`) 4개 모두 통과:
  - `storeNotificationAfterCommit_concurrentRequests_incrementUnreadCounterExactlyOncePerNotification`
  - `notificationCreateAndMarkAllRead_concurrentRequests_keepUnreadCounterAlignedWithUnreadRows`
  - `notificationCreateAndMarkPageRead_concurrentRequests_keepUnreadCounterAlignedWithUnreadRows`
  - `notifyLike_afterCommit_createsNotificationAndIncrementsUnreadCounter`

### 기대 효과

- store/read-page/read-all이 같은 수신자에 대해 병렬 실행 가능
- connection hold time 감소 (lock wait 제거) → pool saturation 완화
- `refreshUnreadNotificationCount`의 COUNT subquery 제거 → read 경로 쿼리 수 감소

## 2026-04-13 HikariCP pool 튜닝 및 OSIV 비활성화

상태: 완료 (soak 재검증 통과)

### 배경

- notification lock 제거 후에도 `dbPoolTimeouts = 132`, `maxThreadsAwaitingConnection = 200` 문제의
  근본 원인인 connection 점유 시간이 남아 있었다.
- Spring Boot 기본값 `spring.jpa.open-in-view = true`는 DB connection을
  HTTP 요청의 전체 수명(서비스 로직 + Thymeleaf 렌더링 + 응답 전송) 동안 점유한다.
- loadtest profile의 `connection-timeout: 1000ms`는 burst 시 과도하게 빠른 timeout 유발.

### 원인 분석

**OSIV (Open Session In View)**:
- OSIV on: connection 점유 = 요청 시작 ~ 응답 완료 (뷰 렌더링 포함)
- OSIV off: connection 점유 = `@Transactional` 진입 ~ 종료
- 뷰 렌더링 시간(수~수십 ms)만큼 connection hold time이 불필요하게 늘어남
- 28개 pool 에서 요청당 hold time이 10ms만 줄어도 초당 280개 connection-turn이 추가 확보됨

**서비스 레이어 DTO 변환 확인**:
- 모든 웹 컨트롤러는 서비스 레이어에서 DTO를 받아 모델에 전달
- 유일한 예외: `ReportService.getPendingReports()` / `getResolvedReports()`가 `Report` 엔티티를 반환하고
  admin 템플릿이 `r.reporter.username`으로 lazy association 접근
- `AdminWebController`의 `List<User>`는 직접 필드만 접근 (lazy relation 없음)

### 적용한 변경

**1. `open-in-view: false` (application.yml)**

```yaml
spring.jpa.open-in-view: false
```

**2. `Report.reporter` lazy loading 수정 (ReportRepository)**

OSIV 비활성화로 admin 템플릿의 `r.reporter.username` 접근이 `LazyInitializationException`을
일으킬 수 있어 `JOIN FETCH` 쿼리 추가:

```java
@Query("SELECT r FROM Report r JOIN FETCH r.reporter WHERE r.status = :status ORDER BY r.createdAt ASC")
List<Report> findByStatusWithReporterOrderByCreatedAtAsc(@Param("status") ReportStatus status);

@Query("SELECT r FROM Report r JOIN FETCH r.reporter WHERE r.status IN :statuses ORDER BY r.resolvedAt DESC")
List<Report> findByStatusInWithReporterOrderByResolvedAtDesc(@Param("statuses") List<ReportStatus> statuses);
```

**3. HikariCP loadtest 설정 조정 (application-loadtest.yml)**

| 설정 | 이전 | 이후 | 근거 |
|------|------|------|------|
| `maximum-pool-size` | 28 | 28 (유지) | OSIV off + lock 제거 후 같은 pool 이 더 효율적 |
| `minimum-idle` | 10 | 10 (유지) | 변경 불필요 |
| `connection-timeout` | 1000ms | 2000ms | burst 흡수 여유 확보 |
| `leak-detection-threshold` | (없음) | 5000ms | 5초 이상 미반환 connection 경고 로깅 |

### 측정 (테스트 결과)

- 전체 테스트 통과 (단위 + 통합 + 동시성)
- OSIV off 후 `LazyInitializationException` 발생 없음 확인
- soak 재검증은 별도로 실행 필요

### Soak 재검증 결과 (2026-04-14)

- 실행 시각: `20260414-052612`
- artifact: `loadtest/results/soak-20260414-052612-server.json`
- 조건: `0.95 / 15m` (이전 FAIL이었던 동일 조건)
- **status: PASS**

#### 전후 비교

| 메트릭 | 이전 (03/28, FAIL) | 이후 (04/14, PASS) | 변화 |
|--------|-------------------|-------------------|------|
| `dbPoolTimeouts` | 132 | **0** | 완전 제거 |
| `maxThreadsAwaitingConnection` | 200 | 190 | -10 |
| `unexpected_response_rate` | 0.0001 | **0.0000** | 완전 제거 |
| `duplicateKeyConflicts` | 0 | 0 | 유지 |
| `unreadNotificationMismatchUsers` | 0 | 0 | 유지 |
| `http p95` | 240.01ms | 315.09ms | +75ms |
| `http p99` | 302.59ms | 393.70ms | +91ms |

#### 해석

- **`dbPoolTimeouts` 132 → 0**: OSIV 비활성화로 connection hold time이 `@Transactional` 범위로 축소되고,
  `connection-timeout`을 1s → 2s로 올려 burst 대기 여유가 확보됨. pool size 28 유지만으로 충분.
- **FAIL → PASS**: pool timeout으로 발생하던 실패 응답이 완전 제거되어 strict pass 달성.
- **p95/p99 증가**: 이전에 즉시 timeout 에러(빠른 실패 응답)로 처리되던 요청이 이제 대기 후 정상 완료됨.
  latency 분포가 우측으로 이동한 것은 건강한 현상이다.
- **notification 경로**: `notification.store` avgWall=1.82ms, `notification.read-page.summary` avgWall=2.58ms.
  lock-recipient 단계가 제거돼 이전 대비 wall time이 크게 감소.
- **정합성**: 모든 mismatch/conflict 메트릭 0. 데이터 무결성 완벽 유지.

#### 적용한 최적화 3건의 기여도 분석

| 최적화 | 주요 기여 |
|--------|----------|
| OSIV 비활성화 | connection hold time 단축 → pool timeout 근본 제거 |
| HikariCP connection-timeout 2s | burst 시 대기 여유 → 일시적 pool 포화를 timeout 없이 흡수 |
| notification FOR UPDATE lock 제거 | lock 경합 제거 → connection 점유 시간 추가 단축 |

### Soak 재측정 결과 (2026-04-19) — 0.95 / 2시간 (post-OSIV 최초)

- 실행 시각: `20260419-200547`
- artifact: `loadtest/results/soak-20260419-200547.md` / `-server.json` / `-metrics.jsonl`
- 콘솔 로그 (10분 progress tick): `loadtest/results/soak-run-20260419-200440.console.log`
- 조건: `SOAK_FACTOR=0.95`, `SOAK_DURATION=2h`, `WARMUP_DURATION=2m`, `PROGRESS_INTERVAL_SECONDS=600`
- **status: FAIL (k6 per-scenario threshold crossing)** — 아래 해석 참조
- 인프라 레벨은 완전 통과: `dbPoolTimeouts=0`, `duplicateKeyConflicts=0`, 모든 SQL invariant=0, `unexpected_response_rate=0.0000`

#### 이전 `0.95 / 2h` 시도와의 비교

이전 0.95/2h 시도는 `soak-20260327-232958` (pre-OSIV + FOR UPDATE lock 유지 상태)로 pool timeout 69건과 search 벽시계 drift로 FAIL.

| 메트릭 | 이전 2h (03-27, pre-OSIV) | 이번 2h (04-19) | 변화 |
|--------|-----------------------|------------------|------|
| `dbPoolTimeouts` | 69 | **0** | 완전 제거 |
| `maxThreadsAwaitingConnection` | 200 | 193 | -7 |
| `unexpected_response_rate` | 0.0000 | 0.0000 | 유지 |
| `duplicateKeyConflicts` / invariants | 0 | 0 | 유지 |
| `post.list.search.keyword.board.rows` avgWall | 21.17ms | **3.37ms** | **-84%** (search 경로 최적화 + 2h sustained 안정) |
| `post.list.search.keyword.board.rows` maxWall | 62.31ms | 78.60ms | +26% (꼬리 증가했으나 평균은 크게 안정) |
| `notification.store` avgWall | 2.29ms | 2.00ms | -13% |
| `notification.read-page.summary` avgWall | 3.54ms | 2.71ms | -23% |
| `http p95` | 274.74ms | 350.06ms | +75ms |
| `http p99` | 347.57ms | 468.02ms | +120ms |

#### FAIL 원인 분해

k6 summary에서 아래 세 개 scenario threshold가 교차하면서 k6 exit code가 0이 아니게 되어 runner가 FAIL로 판정:

```
thresholds on metrics 'block_mixed_duration, bookmark_mixed_duration, follow_mixed_duration' have been crossed
```

| scenario | p95 | p99 | threshold (p95) |
|----------|-----|-----|-----------------|
| `block_mixed_duration` | 417.12ms | 539.41ms | <400ms (추정) |
| `bookmark_mixed_duration` | 418.02ms | 541.40ms | <400ms (추정) |
| `follow_mixed_duration` | 427.89ms | 552.82ms | <400ms (추정) |

모두 `FOR UPDATE` 기반 relation toggle scenario. infrastructure 지표(pool, 정합성)는 모두 통과했으나 2h sustained 0.95 부하에서 per-scenario p95가 400ms 한계를 17–28ms 초과.

#### 해석

- **OSIV/pool/lock 3종 세트가 2h sustained에서 유효함이 확인됐다.** pre-OSIV 2h 시도의 실패 원인(~100분 지점 search drift + pool timeout storm)은 완전히 재현되지 않는다. search 벽시계는 2h 내내 3.37ms 근처에서 평평하게 유지됐고(progress tick: longest tx 15–28ms 범위로 소폭 oscillation만), pool timeout은 끝까지 0이다.
- **새 병목 후보: `post.like.add` / `post.like.remove`.** avgWall 13.29ms / 13.32ms, sample 각 3.2M — 전체 wall time에서 단일 최대 비중. 이전 2h에서는 search가 dominant(21ms × 24만 samples)였다면, 최적화 후 `like` race 경로가 새로 드러난 ceiling으로 이동했다. `post_likes` 테이블 unique 제약·중복 catch·counter update 경로가 sustained 0.95에서 15ms급 wall time을 쓴다.
- **p95/p99 drift는 정상 범위**: 0.95/15m 대비 `http p95` 315→350ms (+35ms), `http p99` 394→468ms (+74ms). 기존 15m→2h 확장에서 관찰되는 자연적 drift로 보이며, pool timeout/conflict가 없는 상태에서 queue depth가 지속되는 현상.
- **k6 threshold 자체는 pre-OSIV baseline에 맞춰져 있어** 2h × 0.95 post-OSIV 현황에서는 tight함. block/bookmark/follow `FOR UPDATE` 경로를 `post.like.*`와 동일한 atomic 스타일로 전환하는 최적화가 다음 단계 자연스러운 target.

#### 다음 단계 후보

1. `post.like.add/remove` 경로 분해(sub-profile 추가)로 15ms 원인 지점(FOR UPDATE lock vs counter update vs duplicate catch)을 식별
2. `bookmark/follow/block` toggle 경로도 notification과 동일하게 atomic increment·unique conflict retry로 전환 검토
3. 재측정 후 per-scenario p95 threshold 재설정(현 post-OSIV 베이스라인 반영)

#### 운영 메모

- 콘솔에 10분 간격 progress 출력 기능을 `run-bbs-soak.sh` sampler에 `PROGRESS_INTERVAL_SECONDS` (기본 600) 환경 변수로 내장. 기본값으로 장시간 소크에서 자동 progress가 기록된다.
- `run-bbs-managed-soak.sh`는 bootRun 기동 → readiness → k6 → cleanup → SIGTERM 순서로 lifecycle을 책임지므로 2h 런 중 wrapper를 중단하지 말 것. 이전 시도에서 cleanup이 120s를 넘겨 timeout났지만 summary는 정상 생성되는 best-effort 구조는 이번에도 동일하게 동작.

### Block/Bookmark/Follow toggle FOR UPDATE lock 제거 (2026-04-19, commit `0af8873`)

이전 2h FAIL의 직접 원인인 `block_mixed` / `bookmark_mixed` / `follow_mixed` p95 threshold crossing을 해결하기 위해, 세 경로의 "requester User 행 lock → exists check → save" 패턴을 dialect-aware atomic upsert로 교체했다.

#### 변경 내용

| 영역 | 이전 | 이후 |
|------|------|------|
| requester User lock | `findByUsernameForUpdate()` (PESSIMISTIC_WRITE) | 제거 |
| insert | JPA `save()` → unique 제약 후 catch | `RelationAtomicInserter` (PostgreSQL `ON CONFLICT DO NOTHING` / H2 `MERGE`) |
| delete | `findByFollowerAndFollowee(...)` → entity delete | `@Modifying @Query` bulk DELETE (1 round trip) |
| 영향 범위 | `BlockService.toggleBlock` / `BookmarkService.toggleBookmark` / `FollowService.toggleFollow` | 동일 3종 |

unique 제약(`(blocker_id, blocked_id)` 등)이 동시성을 실질적으로 보장하므로, requester User 행을 lock하는 건 불필요한 serialization이었다. notification 경로와 동일한 atomic 스타일로 통일.

#### 측정: Soak 0.95 / 2h 확인 (2026-04-20, post-lock-removal)

- 실행 시각: `20260420-000357`
- artifact: `loadtest/results/soak-20260420-000357.md` / `-server.json` / `-metrics.jsonl` / `-k6.json`
- 콘솔 로그 (10분 progress tick): `loadtest/results/soak-run-20260420-000345.console.log`
- 조건: `SOAK_FACTOR=0.95`, `SOAK_DURATION=2h`, `WARMUP_DURATION=2m`, `PROGRESS_INTERVAL_SECONDS=600`
- **status: PASS**

##### 이전 2h (04-19, FAIL) 대비 비교

| 메트릭 | 04-19 2h (FAIL) | 04-20 2h (PASS) | 변화 |
|--------|-----------------|-----------------|------|
| `http p95` | 350.06ms | **331.26ms** | -18.8ms |
| `http p99` | 468.02ms | **399.81ms** | -68.2ms |
| `block_mixed_duration` p95 | 417.12ms | **378.05ms** | **-39.1ms (threshold <400ms 통과)** |
| `bookmark_mixed_duration` p95 | 418.02ms | **378.43ms** | **-39.6ms (통과)** |
| `follow_mixed_duration` p95 | 427.89ms | **382.77ms** | **-45.1ms (통과)** |
| `block_mixed_duration` p99 | 539.41ms | 441.64ms | -97.8ms |
| `bookmark_mixed_duration` p99 | 541.40ms | 441.94ms | -99.5ms |
| `follow_mixed_duration` p99 | 552.82ms | 446.96ms | -105.9ms |
| `dbPoolTimeouts` | 0 | 0 | 유지 |
| `duplicateKeyConflicts` / invariants | 0 | 0 | 유지 |
| `unexpected_response_rate` | 0.0000 | 0.0000 | 유지 |
| `maxThreadsAwaitingConnection` | 193 | 191 | -2 |

k6 per-scenario threshold가 한 건도 crossing되지 않고 2h 내내 안정. 10분 progress tick은 내내 `dbPoolTimeouts=0 duplicateKeyConflicts=0 maxActiveConnections=28 longestTransactionMs=15–43ms` 범위를 유지.

##### 해석

- **원인 가설 적중**: requester User 행 lock 제거만으로 3종 scenario p95가 일제히 39–45ms 하락. FOR UPDATE lock이 실제로 2h sustained 0.95 부하에서 wait event를 만들고 있었음이 확인됨.
- **collateral 개선**: 3종 경로의 lock 제거가 connection hold time을 줄이면서 전체 `http p95` -18ms / `http p99` -68ms까지 함께 좋아졌다. p99 개선폭이 크다는 건 꼬리 분포(lock 대기로 튀던 소수 요청)가 정리됐다는 의미.
- **cleanup 409**: `cleanup endpoint timed out or returned non-2xx`는 이전과 동일한 best-effort 실패 — summary는 captured artifacts에서 정상 생성.

#### 확정된 새 dominant bottleneck: `post.like.add` / `post.like.remove`

이번 2h에서 profile 상위는 여전히 like 경로다:

| profile | sampleCount | avgWallMs | maxWallMs | avgSqlMs | avgStmts |
|---------|-------------|-----------|-----------|----------|----------|
| `post.like.add` | 3,224,906 | **15.19** | 677.98 | 14.48 | 4.00 |
| `post.like.remove` | 3,234,377 | **15.18** | 598.79 | 14.47 | 4.00 |
| `comment.create` | 1,324,849 | 2.70 | 54.01 | 1.55 | 4.98 |
| `notification.store` | 1,303,484 | 1.85 | 124.61 | 1.06 | 3.00 |

`post.like.add/remove` 단일 경로가 전체 wall-time 지분 최대(sample × avg 기준). 다음 최적화 타겟은 이 경로이며, 4-SQL 중 어느 단계(exists check / insert / counter update / duplicate catch)가 15ms를 쓰는지 sub-profile 분해 필요.

#### 다음 단계 후보

1. `post.like.add/remove` 경로 sub-profile 분해 (resolve-post / unique-check / insert / counter-update) 로 15ms 원인 지점 식별
2. `post_likes` atomic upsert + `post.likeCount` atomic update로 전환 검토 (block/bookmark/follow와 동일 스타일)
3. 재측정 후 per-scenario p95 threshold 재설정 (현 post-lock-removal 베이스라인 반영)

### `post.like.add/remove` sub-profile 분해 측정 (2026-04-20)

`PostService.likePost` / `unlikePost` 내부를 6–7개 sub-profile로 분해해 15ms avgWall의 원인 지점을 식별.

- 계측 대상: `resolve-post` / `resolve-user` / `exists-check` / `insert` / `counter-increment` / `notify` / `response` (add) 및 `resolve-post` / `resolve-user` / `delete` / `counter-decrement` / `response` (remove)
- 실행 시각: `20260420-023930`
- artifact: `loadtest/results/soak-20260420-023930.md` / `-server.json` / `-metrics.jsonl` / `-k6.json`
- 콘솔 로그: `loadtest/results/soak-run-20260420-023919.console.log`
- 조건: `SOAK_FACTOR=0.95`, `SOAK_DURATION=2h`, `WARMUP_DURATION=2m`, `PROGRESS_INTERVAL_SECONDS=600`
- **status: PASS** (http p95 331.63ms, p99 397.11ms — 직전 baseline 331.26/399.81ms와 동일. 계측 오버헤드 측정 불가)

#### 측정 결과: sub-profile 브레이크다운

**`post.like.add` (avgWall 15.41ms, n=3,212,641)**

| sub-step | sampleCount | avgWallMs | maxWallMs | avgSqlMs | 점유율 |
|----------|-------------|-----------|-----------|----------|--------|
| `resolve-user` (FOR UPDATE) | 3,212,641 | **14.33** | 66.96 | 14.11 | **93%** |
| `resolve-post` | 3,212,641 | 0.39 | 17.59 | 0.22 | 2.5% |
| `exists-check` | 3,212,641 | 0.36 | 9.81 | 0.19 | 2.3% |
| `response` | 3,212,641 | 0.31 | 16.55 | 0.16 | 2.0% |
| `insert` | 2 | 1.77 | 1.78 | 0.75 | — |
| `counter-increment` | 2 | 1.52 | 1.81 | 0.40 | — |
| `notify` | 2 | 1.91 | 2.45 | 0.51 | — |

**`post.like.remove` (avgWall 15.08ms, n=3,226,058)**

| sub-step | sampleCount | avgWallMs | maxWallMs | avgSqlMs | 점유율 |
|----------|-------------|-----------|-----------|----------|--------|
| `resolve-user` (FOR UPDATE) | 3,226,058 | **14.01** | 88.16 | 13.79 | **93%** |
| `resolve-post` | 3,226,058 | 0.39 | 17.20 | 0.23 | 2.6% |
| `delete` | 3,226,058 | 0.36 | 15.35 | 0.18 | 2.4% |
| `response` | 3,226,058 | 0.31 | 15.00 | 0.16 | 2.1% |
| `counter-decrement` | 1 | 3.75 | 3.75 | 1.37 | — |

#### 핵심 발견

1. **`findByUsernameForUpdate()`가 15ms의 93%를 독식.** avgSqlMs (14.11 / 13.79ms) ≈ avgWallMs 이므로, SQL execution time 내부에 lock wait가 포함된 것. block/bookmark/follow에서 제거했던 것과 **동일한 requester User 행 FOR UPDATE convoy 패턴**.

2. **state 변경 경로는 거의 실행되지 않는다.** 3.2M likes 중 `insert` / `counter-increment` / `notify` sample이 각 1~2건, `delete`+`counter-decrement`도 1건. 즉 k6 시나리오의 거의 모든 요청이 "이미 좋아요 상태 → no-op" 또는 "안 누름 상태 → no-op" 경로로 떨어진다. 그럼에도 **no-op인 요청조차 FOR UPDATE로 직렬화**되고 있었던 것.

3. **해법은 이미 검증된 패턴**: unique 제약 `(post_id, user_id)` 기반 atomic upsert (`ON CONFLICT DO NOTHING` / `MERGE`) + bulk DELETE + atomic counter UPDATE. `post.likeCount` atomic UPDATE는 이미 있으므로 `findUserForUpdate` 제거 + `postLikeRepository`를 `RelationAtomicInserter` 스타일로 전환하면 된다.

#### 예상 효과

- `post.like.add` / `post.like.remove` avgWall 15ms → ~1ms (resolve-user 경로 소거)
- 해당 시나리오 `like_add_race` / `like_remove_race` p95 221.11 / 220.66ms → ~210ms 아래 (queue depth 감소)
- http p95 전체도 15–20ms 추가 개선 가능성 (전체 wall-time 지분 최대 경로이므로)

#### 다음 단계

1. `post.like.add/remove` 경로를 block/bookmark/follow와 동일하게 atomic upsert/delete로 전환 (`findUserForUpdate` 제거, `RelationAtomicInserter` 도입)
2. 전환 후 동일 조건(0.95 / 2h) 재측정으로 예상 효과 확정
3. 개선 폭이 충분하면 per-scenario p95 threshold도 재조정 검토

### `post.like` FOR UPDATE lock 제거 + atomic upsert/delete 전환 (2026-04-20)

sub-profile 분해에서 확정된 원인(`resolve-user` FOR UPDATE 가 15ms 중 14ms 독식)을 block/bookmark/follow와 동일한 atomic 패턴으로 해소.

#### 변경 내용

| 영역 | 이전 | 이후 |
|------|------|------|
| requester User lock | `userLookupService.findByUsernameForUpdate()` (PESSIMISTIC_WRITE) | `userLookupService.findByUsername()` (non-locking) |
| state 확인 + 삽입 | `existsByPostAndUser()` → `postLikeRepository.save(new PostLike(...))` (2 SQL, race 시 unique 위반) | `RelationAtomicInserter.insertPostLike(postId, userId, now)` (1 SQL, atomic) |
| delete | derived `deleteByPostAndUser` (SELECT 후 em.remove) | `@Modifying @Query` bulk DELETE (1 SQL, 0 또는 1 row affected 반환) |
| counter / notify 게이팅 | `!exists` 분기 | `inserted > 0` / `deleted > 0` 반환값 분기 |
| 영향 범위 | `PostService.likePost` / `PostService.unlikePost` | 동일 2개 메서드 |

#### Dialect별 atomic insert 구현

`RelationAtomicInserter.insertPostLike`는 "실제 신규 삽입 건수(0 또는 1)"를 정확히 반환해야 counter increment 와 notification 발행을 올바르게 게이팅할 수 있다. block/bookmark/follow 와 달리 **side-effect (likeCount UPDATE + notify event)** 가 있기 때문에 insert 반환값의 정확성이 중요하다.

- **PostgreSQL**: `INSERT ... ON CONFLICT (post_id, user_id) DO NOTHING` — 충돌 시 0, 신규 삽입 시 1 반환. race-free.
- **H2 2.4.240 (테스트)**: `ON CONFLICT` 미지원. `MERGE INTO ... KEY (...) VALUES (...)` 은 기존 row 에도 update count = 1 을 보고하므로 사용 불가. 대신 `INSERT INTO ... SELECT ... WHERE NOT EXISTS` 구문을 사용해 0/1 을 정확히 반환하고, 동시 실행으로 둘 다 `WHERE NOT EXISTS` 를 통과하는 race 는 unique 제약이 나중 INSERT 를 차단 → `DuplicateKeyException` 을 캐치해 0 으로 해석.

#### 예상 효과 (재측정 전)

| profile | sub-profile 측정값 | 예상값 |
|---------|-------------------|--------|
| `post.like.add` avgWall | 15.41ms | ~1.0ms (resolve-user FOR UPDATE 제거로 −14ms) |
| `post.like.remove` avgWall | 15.08ms | ~1.0ms (동일) |
| `like_add_race_duration` p95 | 221.11ms | 210ms 미만 |
| `like_remove_race_duration` p95 | 220.66ms | 210ms 미만 |

#### 단위 테스트

전체 376 tests pass. `PostServiceTest`는 `RelationAtomicInserter.insertPostLike` mock 반환값(1 또는 0)을 기반으로 counter/notify 게이팅을 검증하고, `WritePathConcurrencyTest.likePost_concurrentDuplicateRequests` 는 실제 H2에서 8개 스레드 동시 like 시 단일 row + likeCount=1 을 보장함을 확인.

#### 다음 단계

1. 동일 조건(0.95 / 2h) 재측정으로 `post.like.add/remove` avgWall 하락 및 k6 per-scenario p95 개선 확정
2. 예상대로 하락 시 per-scenario threshold 재조정 검토 (post-optimization baseline 반영)

### `post.like` atomic 전환 2h/0.95 재측정 (2026-04-20)

`soak-20260420-051735.md` — `post.like` FOR UPDATE 제거 + atomic upsert/bulk DELETE 전환 직후 2h/0.95 재측정. **모든 per-scenario threshold PASS**, invariant 전부 0(postLikeMismatchPosts, duplicateKeyConflicts, dbPoolTimeouts 등).

#### `post.like` sub-profile (FOR UPDATE 제거 전 vs 후)

직전 측정(`soak-20260420-023930`, sub-profile + lock on) 대비.

| step | samples | pre avgWall | post avgWall | 감소 |
|------|---------|-------------|--------------|------|
| `post.like.add` | 5,288,552 | **15.41ms** | **2.52ms** | −83.7% |
| `post.like.add.resolve-user` | 5,288,552 | **14.33ms** | **0.96ms** | −93.3% |
| `post.like.add.resolve-post` | 5,288,552 | 0.74ms | 0.61ms | −17.6% |
| `post.like.add.insert` (구 `exists+save`) | 5,288,552 | 0.17+0.05 ms | **0.40ms** (1 SQL) | SQL 2→1, atomic |
| `post.like.remove` | 5,312,816 | **15.08ms** | **2.83ms** | −81.2% |
| `post.like.remove.resolve-user` | 5,312,816 | ~14.3ms | **0.96ms** | −93% |
| `post.like.remove.delete` | 5,312,816 | ~0.24ms (derived) | **0.79ms** (bulk DELETE) | +0.55ms |

해석:
- **resolve-user FOR UPDATE 제거가 예측대로 −13ms.** sub-profile 분해에서 원인으로 지목한 경로가 정확히 사라짐.
- insert 경로는 SQL 1개로 합쳐지며 **원자적 0/1 반환**을 얻었고, 평균 0.40ms 로 이전 "exists + save" 합(0.22ms) 대비 미세 증가하지만 lock 경합 소거로 얻은 게인이 압도.
- delete 경로는 bulk DELETE(0.79ms)가 derived delete(0.24ms)보다 평균은 약간 높지만 **stale entity 문제 + concurrent StaleObjectState 예외 리스크를 근본 제거**한 trade-off. counter-decrement/increment 게이팅도 `affected rows > 0` 반환으로 정확.
- counter-increment/notify 발생 횟수: 5,288,552 샘플 중 **2건** (add), 5,312,816 샘플 중 **1건** (remove) — 99.99996% 가 no-op idempotent 경로임이 재확인됨.

#### k6 per-scenario p95 (pre vs post)

| scenario | pre p95 | post p95 | 감소 | threshold |
|----------|---------|----------|------|-----------|
| `like_add_race_duration` | 221.38ms | **175.86ms** | −45.52ms (−20.6%) | <400 ✅ |
| `like_remove_race_duration` | 220.90ms | **175.32ms** | −45.58ms (−20.6%) | <400 ✅ |
| `block_mixed_duration` | 378.30ms | **283.45ms** | −94.85ms (−25.1%) | <400 ✅ |
| `bookmark_mixed_duration` | 379.01ms | **284.73ms** | −94.28ms (−24.9%) | <400 ✅ |
| `follow_mixed_duration` | 382.74ms | **295.94ms** | −86.80ms (−22.7%) | <400 ✅ |
| `http_req_duration` | 331.63ms | **242.03ms** | −89.60ms (−27.0%) | — |
| `http_req_duration` p99 | 397.11ms | **312.34ms** | −84.77ms (−21.3%) | — |

`like_*_race` 는 예상 "210ms 미만"을 **훌쩍 넘어 175ms** 로 내려왔다. 동시에 직접 경로가 아닌 block/bookmark/follow 시나리오도 −90ms 대 개선이 발생했는데, `post.like` 경로가 FOR UPDATE 경합으로 HikariCP 풀과 tomcat worker 를 점유하고 있던 것이 해소되면서 **다른 쓰기 경로 queue depth 도 동반 감소**한 파급 효과로 해석된다.

#### 소크 안정성 (full 2h)

- `dbPoolTimeouts=0`, `duplicateKeyConflicts=0`, `relationDuplicateRows=0`
- `postLikeMismatchPosts=0`, `postCommentMismatchPosts=0`, `unreadNotificationMismatchUsers=0`
- `maxActiveConnections=28` (풀 상한 도달 지속, 정상), `maxThreadsAwaitingConnection=190`
- `longestTransactionMs`: 10m=13, 20m=16, 30m=13, 40m=10, 50m=4, 60m=8, 70m=9, 80m=8, 90m=6, 100m=25(일시 스파이크), 110m=6, 120m=**1**
  - 이전 런의 10-43ms 대역 대비 4-16ms(+ 1회 25ms) 로 체감 절반. tx tail 도 lock 제거로 개선됨.

#### 결론

`post.like` FOR UPDATE 제거 + atomic upsert/bulk DELETE 전환은 **예측을 초과 달성**. 2h 소크 full PASS, invariant 위반 0, sub-profile avgWall 목표치 달성. block/bookmark/follow 와 동일 패턴이 `post.like` (counter+notify side-effect 포함)에도 통용됨을 확인.

#### 다음 단계 후보

1. 새로운 dominant bottleneck 식별 — `comment.create` 7.41ms / `notification.read-page.summary` 6.24ms / `post.create` 4.90ms 가 현재 top.
2. per-scenario threshold 재조정 — 현 threshold(p95<400 / p99<700 등)가 post-atomic 실측(175ms 대)에 비해 과하게 느슨함. regression gate 로서 실효성 확보를 위한 tightening 검토.

### `comment.like` 기능 신설 — no-lock atomic 패턴으로 첫 구현 (2026-04-23)

`post.like` 에서 확정된 atomic upsert + bulk DELETE 패턴을 **첫 구현부터 그대로 적용**해 댓글 좋아요 기능을 추가. 기능 관점에서는 긴 스레드의 좋은 답변을 가시화할 수단이 생겼고, 부하 관점에서는 lock 경합 없이 `post.like` 와 동일 수준의 avgWall 을 확보하는 것이 목표.

#### 변경 내용

| 영역 | 추가 / 수정 |
|------|-------------|
| 도메인 | `CommentLike` 신설 (`comment_id, user_id` unique, `idx_commentlike_user`); `Comment.likeCount` 컬럼 추가 (counter는 `@Modifying` 쿼리로만 갱신) |
| Repository | `CommentLikeRepository` (bulk `deleteByCommentAndUser`, `deleteByUser`, `deleteByCommentAuthor`); `CommentRepository` 에 `incrementLikeCount/decrementLikeCount/findLikeCountById` 추가 |
| `RelationAtomicInserter` | `insertCommentLike(commentId, userId, createdAt)` — PG `ON CONFLICT DO NOTHING`, H2 `INSERT … SELECT … WHERE NOT EXISTS` + `DuplicateKeyException`=0 해석 |
| Projection | `CommentViewRow` 에 `getLikeCount`, `getLikedByMe` 추가 후, `findTopLevelCommentRowsByPostId…` / `findReplyRowsByParentIdIn…` 두 쿼리에 `:viewerUserId` 바인딩 (`CASE WHEN EXISTS …`). 비로그인 = sentinel `-1L` |
| Service | `CommentService.likeComment/unlikeComment` — `PostService.likePost/unlikePost` 구조 복제, step profiler 이름 `comment.like.add.*` / `comment.like.remove.*` |
| Notification | `NotificationType.COMMENT_LIKE` 신설; `NotificationService.notifyCommentLike(Comment, String)` (self-like skip, 링크 `/posts/{postId}#comment-{commentId}`) |
| DTO/Controller | `CommentResponse` 에 `likeCount/likedByMe` 필드; REST `POST|DELETE /api/posts/{postId}/comments/{id}/like`; 웹 `POST /posts/{postId}/comments/{id}/like|unlike` |
| Template | `post/detail.html` — 최상위 / 대댓글 메타 영역에 ♡/♥ 버튼 + count, 비로그인 시 숫자만 표시 |
| 계정 탈퇴 | `UserService.withdraw` cascade 체인에 `commentLikeRepository.deleteByUser / deleteByCommentAuthor` 삽입 |

구조가 `post.like` 와 완전히 대칭이라 side-effect (counter UPDATE + notify) 게이팅 규칙도 동일: **`insert/delete affected rows > 0` 일 때만 counter 와 notify 가 발화**. race 시에도 counter drift 없음.

#### k6 race 시나리오 추가

`loadtest/k6/bbs-load.js` 에 다음을 추가해 post.like race 와 대칭 가드.

- Trend: `comment_like_add_race_duration`, `comment_like_remove_race_duration`
- 함수: `commentLikeAddRace(data)` / `commentLikeRemoveRace(data)` — seed 된 race user/comment 에 `POST|DELETE /api/posts/{postId}/comments/{commentId}/like`
- setup: `createSeedCommentId()` 로 race 용 comment id 확보, remove race 는 사전 like 로 초기 상태 보장
- 시나리오 프로필: `comment_like_idempotent_add_race`, `comment_like_idempotent_remove_race`, `comment_like_mixed`
- threshold: `p(95)<400`, `p(99)<700` (post.like 와 동일)

env fallback (`COMMENT_LIKE_ADD_VUS || LIKE_ADD_VUS`, `… || LIKE_ADD_DURATION`) 덕분에 기존 2h 소크 스크립트 변수 건드릴 필요 없이 comment race 가 자동 포함.

#### 2h / 0.95 소크 결과 (`soak-20260423-033503.md`)

- status: **PASS**
- http p95 **320.41ms** / p99 **388.90ms** (threshold 400/700)
- unexpected_response_rate **0.0000**, http_reqs **26,062,189** (≈3,559 req/s)
- dbPoolTimeouts **0**, duplicateKeyConflicts **0**, relationDuplicateRows **0**
- postLikeMismatchPosts / postCommentMismatchPosts / unreadNotificationMismatchUsers **0** (SQL invariant 유지)
- maxActiveConnections **28** (풀 상한), maxThreadsAwaitingConnection **190** (타임아웃 0)

##### `comment.like` sub-profile vs `post.like` (같은 런)

| step | `comment.like` samples | `comment.like` avgWall | `post.like` samples | `post.like` avgWall |
|------|----------------------:|----------------------:|-------------------:|-------------------:|
| `*.like.add` | 3,638,907 | **2.67ms** | 3,648,836 | **2.71ms** |
| `*.like.add.insert` | 3,638,907 | 0.43ms | 3,648,836 | 0.44ms |
| `*.like.add.resolve-user` | 3,638,907 | 1.05ms | 3,648,836 | 1.06ms |
| `*.like.add.resolve-{comment,post}` | 3,638,907 | 0.63ms | 3,648,836 | 0.65ms |
| `*.like.add.response` | 3,638,907 | 0.55ms | 3,648,836 | 0.56ms |
| `*.like.remove` | 3,635,290 | **3.02ms** | 3,657,375 | **3.03ms** |
| `*.like.remove.delete` | 3,635,290 | 0.84ms | 3,657,375 | 0.84ms |
| `*.like.remove.resolve-user` | 3,635,290 | 1.05ms | 3,657,375 | 1.05ms |

`comment.like` 와 `post.like` 가 sub-profile 단위에서 **거의 동일 분포**. 첫 구현부터 no-lock 패턴을 적용했기 때문에 `post.like` 초기의 FOR UPDATE convoy (resolve-user 14ms) 단계를 아예 건너뛰었다. counter-increment/decrement + notify 샘플이 3.6M 중 각 1~2 건인 것도 post.like 와 동일 — 시나리오가 idempotent no-op 경로를 지배.

##### k6 per-scenario p95 / p99

| scenario | p95 | p99 | threshold |
|----------|-----|-----|-----------|
| `comment_like_add_race_duration` | **273.37ms** | 312.60ms | <400 / <700 ✅ |
| `comment_like_remove_race_duration` | **273.53ms** | 313.05ms | <400 / <700 ✅ |
| `like_add_race_duration` | 272.83ms | 312.14ms | <400 / <700 ✅ |
| `like_remove_race_duration` | 272.43ms | 311.88ms | <400 / <700 ✅ |
| `create_comment_duration` | 331.90ms | 385.07ms | <400 / <700 ✅ |
| `block_mixed_duration` | 369.84ms | 434.94ms | <400 / <700 ✅ |
| `bookmark_mixed_duration` | 371.83ms | 436.56ms | <400 / <700 ✅ |
| `follow_mixed_duration` | 383.07ms | 446.64ms | <400 / <700 ✅ |
| `http_req_duration` | 320.41ms | 388.90ms | — |

post.like 런(175ms 대)에 비해 p95 가 전반적으로 100ms 대 올라 보이는 것은 같은 풀(28)과 tomcat worker 를 comment.like 2종 race 가 **추가로 100VU 이상 공유**해서 생긴 queue 지연이지, 경로 자체의 저하는 아님 — sub-profile avgWall 이 post.like 와 완전 일치하는 것이 근거. p95/p99 모두 threshold 여유 있는 범위.

##### 10분 틱 안정성

| elapsed | dbPoolTimeouts | duplicateKeyConflicts | maxThreadsAwaitingConnection | longestTransactionMs |
|---:|---:|---:|---:|---:|
| 10m | 0 | 0 | 190 | 16 |
| 20m | 0 | 0 | 190 | 8 |
| 30m | 0 | 0 | 190 | 5 |
| 40m | 0 | 0 | 190 | 5 |
| 50m | 0 | 0 | 190 | 9 |
| 60m | 0 | 0 | 190 | 7 |
| 70m | 0 | 0 | 190 | 9 |
| 80m | 0 | 0 | 190 | 8 |
| 90m | 0 | 0 | 190 | 13 |
| 100m | 0 | 0 | 190 | 17 |
| 110m | 0 | 0 | 190 | 7 |
| 120m | 0 | 0 | 190 | 2 |

longestTransactionMs 2–17ms 로 내내 tight, dbPoolTimeouts/duplicateKeyConflicts 는 전 구간 0. `maxThreadsAwaitingConnection=190` 은 피크 시점 스냅샷 값이 누적 유지된 것이지 정체가 누적되는 것이 아니다 (pool 이 풀 활용되는 정상 상태).

##### 운영 주의 (차단 없음)

- cleanup endpoint 가 타임아웃/409 로 실패 (`cleanupStatus: failed`). 캡처된 아티팩트로 summary 는 정상 생성됐고 perf 결과엔 영향 없음. scope 데이터(`soakcl0423033453` prefix) 는 테스트 DB 에 잔류하므로 주기적 수동/자동 cleanup 검토.

#### 결론

`comment.like` 는 첫 구현부터 `post.like` atomic 패턴을 그대로 가져가 **별도 최적화 없이 post.like 와 동일한 avgWall 분포** 를 달성. 2h/0.95 소크 full PASS, invariant 위반 0, 새 race 시나리오도 threshold 여유. 대칭성 확보로 이후 코멘트 좋아요 관련 유지보수 시에도 post.like 와 동일 튜닝 레버를 그대로 사용 가능.

### loadtest cleanup bulk화 + `notification.read-page` deterministic lock (2026-04-26)

`0.95` managed soak 재개 중 두 가지 운영성 문제가 다시 드러났다.

1. 장시간 run 후 cleanup 이 큰 prefix scope 를 안정적으로 비우지 못하거나, FK 관계 때문에 후속 측정 DB를 오염시킬 수 있었다.
2. `notification.read-page` 의 `markPageAsReadByIds` 가 겹치는 unread row 를 동시에 update 할 때 PostgreSQL deadlock(`40P01`)을 만들 수 있었다.

#### 변경 내용

| 영역 | 이전 | 이후 |
|------|------|------|
| cleanup scope 계산 | repository delete 조합에 의존 | prefix users/posts/comments 를 temporary table 에 담아 고정 |
| cleanup delete | 일부 FK 범위 누락 가능 | password reset, notification setting, messages, comment/post likes, bookmarks, follows, blocks, reports, post_tags/images, nested comments, posts, folders, users 를 FK 순서대로 bulk delete |
| cleanup 회귀 테스트 | row 삭제 여부 중심 | FK coverage + SQL statement count `<= 40` 로 사용자별 loop/N+1 방지 |
| read-page mark | JPQL `UPDATE ... WHERE id IN (...)` | native `UPDATE ... WHERE id IN (SELECT ... ORDER BY id FOR UPDATE)` |
| id 입력 순서 | page-state 결과 순서 의존 | service 에서 unread id 를 정렬 후 전달 |
| native query 회귀 테스트 | 없음 | unsorted 입력도 scoped unread row 만 전환하고, 재시도는 `0` 반환 검증 |

`markPageAsReadByIds` 의 핵심은 update 대상 row 를 먼저 `id` 오름차순으로 잠그는 것이다. 단순 `IN (...)` update 는 실행계획에 따라 row lock 순서가 달라질 수 있고, 서로 겹치는 row 집합을 다른 순서로 잡으면 transactionid deadlock 이 가능하다. native query 는 모든 트랜잭션의 lock acquisition order 를 `notifications.id ASC` 로 맞춘다.

#### 변경 전 재현

`soak-20260426-021308.md` (`SOAK_FACTOR=1`, `SOAK_DURATION=10s`) 는 status `FAIL` 이었다.

- `http p95=278.10ms`, `p99=620.78ms`
- `unexpected_response_rate=0.0000`
- `dbPoolTimeouts=0`
- invariant 전부 `0`
- threshold 위반:
  - `browse_list_duration p95=406.85ms`
  - `block_mixed_duration p99=785.67ms`
  - `bookmark_mixed_duration p99=788.87ms`
- 앱 로그에는 `ERROR: deadlock detected`, `SQLState: 40P01`,
  `while locking tuple ... in relation "notifications"` 가 남았다.
- 실패 SQL 은 기존 JPQL update:
  `update notifications ... where recipient_id=? and is_read=false and id in (...)`

즉 첫 짧은 run 은 HTTP 오류율이나 pool timeout 이 아니라,
read-page update 의 row lock 순서 불확정성과 per-scenario latency threshold 가 같이 드러난 사례다.

#### 변경 후 검증

| run | 조건 | status | http p95 | http p99 | dbPoolTimeouts | duplicateKeyConflicts | cleanup |
|-----|------|--------|----------|----------|----------------|-----------------------|---------|
| `soak-20260426-022736` | `1.0 / 10s`, warmup `5s` | `PASS` | `255.81ms` | `333.82ms` | `0` | `0` | `ok` |
| `soak-20260426-023316` | `0.95 / 30m`, warmup `2m` | `PASS` | `269.31ms` | `339.92ms` | `0` | `0` | `ok` |
| `soak-20260426-031511` | `0.95 / 2h`, warmup `2m` | `PASS` | `274.48ms` | `346.48ms` | `0` | `0` | `ok` |

2시간 run 의 k6 summary:

| scenario | p95 | p99 |
|----------|-----|-----|
| `browse_list_duration` | `231.48ms` | `272.32ms` |
| `hot_post_detail_duration` | `257.46ms` | `309.05ms` |
| `search_duration` | `230.65ms` | `271.74ms` |
| `tag_search_duration` | `230.80ms` | `272.06ms` |
| `sort_catalog_duration` | `232.00ms` | `272.86ms` |
| `create_post_duration` | `237.01ms` | `282.13ms` |
| `create_comment_duration` | `289.25ms` | `342.19ms` |
| `like_add_race_duration` | `226.30ms` | `266.57ms` |
| `like_remove_race_duration` | `225.87ms` | `266.45ms` |
| `comment_like_add_race_duration` | `226.71ms` | `267.14ms` |
| `comment_like_remove_race_duration` | `227.02ms` | `267.50ms` |
| `bookmark_mixed_duration` | `329.75ms` | `396.46ms` |
| `follow_mixed_duration` | `342.48ms` | `408.51ms` |
| `block_mixed_duration` | `328.21ms` | `394.55ms` |
| `notification_read_write_mixed_duration` | `283.91ms` | `337.40ms` |

모든 threshold 는 통과했고, `unexpected_response_rate=0.0000` 이었다.

#### N+1 확인

2시간 run final server metrics 기준 read-page 경로는 row 수에 따라 SQL 문 수가 증가하지 않았다.

| profile | avgWallMs | maxWallMs | avgSqlMs | maxSqlMs | avgSqlStatements | maxSqlStatements |
|---------|-----------|-----------|----------|----------|------------------|------------------|
| `notification.read-page.page-state` | `1.00` | `25.32` | `0.77` | `19.77` | `1.00` | `1` |
| `notification.read-page.mark-ids` | `1.68` | `43.58` | `1.39` | `43.31` | `1.00` | `1` |
| `notification.read-page.counter.decrement` | `1.67` | `39.78` | `1.45` | `39.60` | `1.00` | `1` |
| `notification.read-page.summary.transition` | `4.54` | `44.51` | `3.63` | `43.85` | `3.62` | `4` |
| `notification.read-page.summary` | `5.69` | `50.28` | `4.52` | `49.11` | `4.62` | `5` |

cleanup 역시 테스트가 SQL statement count 를 `<= 40` 으로 고정한다. 따라서 현재 변경 기준으로는 `notification.read-page` 와 cleanup 둘 다 사용자 수/row 수에 비례해 select/delete loop 가 늘어나는 N+1 패턴은 관측되지 않았다.

#### 남은 관찰점

- 변경 후 10초 / 30분 / 2시간 앱 로그에서 `deadlock`, `40P01`, `CannotAcquireLock`, pool timeout 은 재현되지 않았다.
- 30분과 2시간 run 의 cleanup 시점에 Hikari leak detection warning 이 1건씩 남았다.
- 2시간 run 은 cleanup 대상이 `241607` posts, `1347607` notifications 였고, DB 잔여 row 확인은 `0|0|0|0` 이었다.
- 따라서 이 warning 은 workload failure 가 아니라 cleanup transaction 이 leak threshold 보다 오래 connection 을 점유한 신호다. 다음 cleanup 개선을 한다면 transaction split 또는 cleanup 전용 leak threshold 조정이 후보지만, 현재 측정의 PASS 판정과 정합성에는 영향을 주지 않았다.
