# Concurrency Consistency

이 문서는 Effective-Disco의 동시성 정합성 검증 결과를 누적 관리하는 living document다.
성능 수치 자체보다 "동시 요청이 들어와도 상태가 깨지지 않는가"를 기준으로 기록한다.

## 목표

- 같은 의도의 중복 요청이 동시에 들어와도 최종 상태가 한 번만 반영될 것
- 반대 의도의 요청이 동시에 들어와도 row 수와 비정규화 카운터가 서로 어긋나지 않을 것
- 커밋 후 부수효과(알림, unread counter, SSE)가 롤백/경합 상황에서도 정합성을 유지할 것
- 검증 결과와 남은 리스크를 다음 단계에서 계속 이어서 갱신할 수 있을 것

## 용어

- `중복 요청 경쟁`: 예) 같은 사용자의 `like` 8개 동시 요청
- `반대 요청 경쟁`: 예) 같은 사용자의 `like` 와 `unlike` 가 동시에 섞여 들어오는 경우
- `정합성 불변식`: 최종적으로 항상 참이어야 하는 조건

## 1차 결과

상태: 완료

검증 범위:

- 게시물 좋아요 등록/해제
- 팔로우 등록/해제
- 북마크 등록/해제
- 차단 등록/해제
- AFTER_COMMIT 알림 생성
- unread notification counter
- 중복 회원가입 경쟁

핵심 결론:

- 같은 의도의 중복 요청 경쟁에서 중복 row, 음수 카운터, 중복 증가가 발생하지 않았다.
- 반대 의도의 요청 경쟁에서도 최종 관계 row 수와 비정규화 카운터가 서로 어긋나지 않았다.
- 알림 생성과 전체 읽음 처리 경쟁에서도 `unread counter == 실제 unread row 수`가 유지됐다.
- 회원가입 유니크 제약 경합에서도 계정 row는 1개만 남았다.

## 2차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- 게시판 목록/정렬 읽기 부하
- 핫 게시물 상세 조회 부하
- 검색 부하
- 게시물/댓글 쓰기 부하
- 같은 사용자의 멱등 좋아요 등록 경쟁
- 같은 사용자의 멱등 좋아요 해제 경쟁
- DB pool 포화 징후와 `duplicate-key` 예외 계측

핵심 결론:

- 스트레스 런에서도 `unexpected_response_rate=0.0000` 으로 기능 오류 응답이 발생하지 않았다.
- 멱등 좋아요 경쟁 시나리오에서도 `duplicateKeyConflicts=0` 이었고, 정합성 깨짐 징후가 관측되지 않았다.
- `dbPoolTimeouts=0` 으로 timeout은 없었지만, `maxActiveConnections=20` 과 `maxThreadsAwaitingConnection=176` 으로 DB connection pool은 이미 포화 구간에 진입했다.
- 이번 2차는 "정합성은 유지되지만 현재 풀 크기 기준으로 대기열이 크게 생긴다"는 점을 확인한 검증이다.

## 3차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- mixed scenario를 포함한 반복 ramp-up 경계점 탐색 5회
- 게시판 목록/핫 게시물/검색/게시물 작성/댓글 작성 혼합 부하
- 멱등 좋아요 등록/해제 경쟁
- 북마크 등록/해제 혼합 경쟁
- 팔로우/언팔로우 혼합 경쟁
- 차단/해제 혼합 경쟁
- 알림 생성 vs 전체 읽음 혼합 경쟁
- `unexpected_response_rate`, `dbPoolTimeouts`, 전체 `http_req_duration p99` 경계점 측정

핵심 결론:

- `1.0x` 와 `1.25x` 는 `5/5 PASS` 였다.
- `1.5x` 는 `3/5 PASS`, `2/5 FAIL` 로 반복 런 기준 안정 구간이 아니었다.
- `1.75x` 는 도달한 `3/3` 런 모두 `LIMIT/FAIL` 이었다.
- 최초 실패 신호는 `duplicate-key` 나 관계 row 정합성 깨짐이 아니라 `unexpected_response_rate > 0`, `dbPoolTimeouts > 0`, 전체 `p99` 급등이었다.
- `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0` 으로 정합성 불변식은 경계 구간에서도 유지됐다.
- 현재 시스템의 보수적 안정 구간은 `1.25x`, 실질 경계 구간은 `1.5x`, 명확한 실패 구간은 `1.75x` 로 판단한다.

## 관련 최적화 기록

- 2026-03-24 `post.list` N+1 최적화를 적용했다.
- 병목 프로파일 기준 `averageSqlStatementCount = 54.84 -> 4.80`, `averageWallTimeMs = 91.81 -> 19.35` 로 줄었다.
- 이번 변경은 정합성 로직 변경이 아니라 DB pool 포화 원인 제거 목적의 읽기 경로 최적화다.
- 2026-03-24 `comment.create` / `notification.store` 쓰기 경로 최적화를 적용했다.
- 병목 프로파일 기준 `comment.create averageSqlStatementCount = 4.95 -> 4.00`, `notification.store averageSqlStatementCount = 3.00 -> 2.00` 으로 줄었다.
- `notification.store averageWallTimeMs = 4.23 -> 1.95` 로 낮아졌고, short soak에서도 `duplicateKeyConflicts=0`, `dbPoolTimeouts=0`, SQL mismatch `0` 이 유지됐다.
- 이후 반복 ramp-up 재측정에서는 `1.0x = 5/5 PASS`, `1.25x = 3/5 PASS`, `1.5x = 3/3 FAIL` 로, write path 최적화만으로 전체 경계점이 올라가지는 않았다.
- 2026-03-24 `JWT 인증 조회`를 `jwt.auth.resolve-user` / `jwt.auth.load-user.db` 로 분리 계측하고, 짧은 TTL 로컬 캐시를 적용했다.
- short soak 기준 `jwtAuthCacheHits=3820`, `jwtAuthCacheMisses=62` 로 인증 요청 대부분은 캐시 hit 로 처리됐고, `jwt.auth.load-user.db averageWallTimeMs = 228.79`, `averageSqlExecutionTimeMs = 0.40` 으로 miss 비용의 대부분이 SQL 자체가 아니라 커넥션 획득 대기임을 확인했다.
- 같은 실행에서도 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0` 으로 정합성 불변식은 유지됐지만 `dbPoolTimeouts=435` 로 DB pool 포화 자체는 여전히 해소되지 않았다.
- 2026-03-24 `20 / 24 / 28 / 32` Hikari pool sweep 결과, 현재 로컬 workload 기준 중앙값은 `28` 이 가장 좋았다.
- `20` 은 `dbPoolTimeouts` 와 오류 응답이 재현됐고, `24/28/32` 는 모두 timeout 없이 버텼지만 `p95/p99` 는 `28` 이 가장 낮았다.
- 이에 따라 loadtest 프로필 기본 Hikari `maximum-pool-size` 를 `28` 로 조정했다.
- 2026-03-24 `sub-1.0` 안정 구간 탐색용 [run-bbs-sub-stability.sh](/home/admin0/effective-disco/loadtest/run-bbs-sub-stability.sh) 를 추가했다.
- 같은 날짜에 `postgresSnapshot` 계측을 넣어 PostgreSQL `wait_event`, 장기 실행 query, longest query/transaction 시간을 `server-metrics` 와 함께 남기도록 확장했다.
- 짧은 sanity 기준 [sub-stability-20260324-060133.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-060133.md) 에서 `0.75`, `0.9` 는 `1/1 PASS` 였고, `postgresSnapshot` 은 idle `ClientRead` 를 과대계상하지 않도록 보정된 상태로 `waitingSessions=0`, `slowActiveQueries=[]` 를 반환했다.
- 이 결과는 "정식 안정 구간 확정" 이 아니라, 반복 탐색 도구와 PostgreSQL 계측이 실제 loadtest 실행에 붙는지 확인한 준비 단계다.
- 상세 원인, 조치, 전후 수치는 [loadtest-optimization.md](loadtest-optimization.md) 에 기록한다.

## 4차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `post.list` 최적화 이후 반복 ramp-up 경계점 재측정 5회
- mixed scenario를 포함한 게시판 목록/핫 게시물/검색/게시물 작성/댓글 작성 혼합 부하
- 멱등 좋아요 등록/해제 경쟁
- 북마크 등록/해제 혼합 경쟁
- 팔로우/언팔로우 혼합 경쟁
- 차단/해제 혼합 경쟁
- 알림 생성 vs 전체 읽음 혼합 경쟁
- `unexpected_response_rate`, `dbPoolTimeouts`, 전체 `http_req_duration p99` 재측정

핵심 결론:

- `1.25x` 는 최적화 이후에도 `5/5 PASS` 였다.
- `1.5x` 는 최적화 이후 `5/5` 모두 `LIMIT/FAIL` 이었다.
- `1.5x` 에서 `2/5` 는 `http-p99-threshold`, `3/5` 는 `unexpected-response` 와 `dbPoolTimeouts` 동반으로 멈췄다.
- `1.5x` 전체 `p99` 는 `868.06ms~963.63ms`, 평균 `904.64ms` 였다.
- `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0` 으로 정합성 불변식은 최적화 후에도 계속 유지됐다.
- 결론적으로 `post.list` 최적화는 국소 병목은 줄였지만, 시스템 전체 보수적 안정 구간은 아직 `1.25x` 다.

## 5차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `comment.create` / `notification.store` 최적화 이후 반복 ramp-up 경계점 재측정 5회
- mixed scenario를 포함한 게시판 목록/핫 게시물/검색/게시물 작성/댓글 작성 혼합 부하
- 멱등 좋아요 등록/해제 경쟁
- 북마크 등록/해제 혼합 경쟁
- 팔로우/언팔로우 혼합 경쟁
- 차단/해제 혼합 경쟁
- 알림 생성 vs 전체 읽음 혼합 경쟁
- `unexpected_response_rate`, `dbPoolTimeouts`, 전체 `http_req_duration p99` 재측정

핵심 결론:

- `1.0x` 는 `5/5 PASS` 였다.
- `1.25x` 는 `3/5 PASS`, `2/5 LIMIT` 이었다.
- `1.5x` 는 도달한 `3/3` 런 모두 `FAIL` 이었다.
- `1.25x` LIMIT 는 모두 `http-p99-threshold` 였고, `1.5x` FAIL 은 모두 `unexpected-response` 와 `dbPoolTimeouts` 를 동반했다.
- `1.25x` 전체 `p99` 는 `774.08ms~961.15ms`, `1.5x` 전체 `p99` 는 `1046.84ms~1581.89ms` 였다.
- `1.5x` 실패 런의 `dbPoolTimeouts` 는 `4~79`, `maxActiveConnections` 는 매번 `20`, `maxThreadsAwaitingConnection` 은 `191~200` 이었다.
- `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0` 으로 정합성 불변식은 이번 재측정에서도 유지됐다.
- 결론적으로 `comment.create` / `notification.store` 최적화는 로컬 write path 비용은 줄였지만, 현재 반복 ramp-up 기준 전체 보수적 안정 구간을 `1.25x` 이상으로 밀어 올리지는 못했다.

## 6차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `pool=28` 기준 `sub-1.0` 안정 구간 반복 탐색 5회
- stage factor `0.75 / 0.85 / 0.9 / 0.95 / 1.0`
- mixed scenario를 포함한 게시판 목록/핫 게시물/검색/게시물 작성/댓글 작성 혼합 부하
- 멱등 좋아요 등록/해제 경쟁
- 북마크 등록/해제 혼합 경쟁
- 팔로우/언팔로우 혼합 경쟁
- 차단/해제 혼합 경쟁
- 알림 생성 vs 전체 읽음 혼합 경쟁
- `unexpected_response_rate`, `dbPoolTimeouts`, 전체 `http_req_duration p99` 재측정

핵심 결론:

- [sub-stability-20260324-062311.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-062311.md) 기준 `highest stable factor` 는 `n/a` 였다.
- `0.75` 는 `4/5 PASS`, `1/5 FAIL` 이었다.
- `0.85` 는 `1/4 PASS`, `2/4 LIMIT`, `1/4 FAIL` 이었다.
- `0.9` 는 도달한 `1/1` 런이 `FAIL` 이었다.
- `0.75` 실패 런에서는 `unexpected_response_rate=0.0033`, `dbPoolTimeouts=144`, `p99=1350.56ms` 가 관측됐다.
- `0.85` 는 `db-pool-timeout` LIMIT 와 `unexpected-response` FAIL 이 함께 나왔고, `p99` 는 최대 `980.46ms` 였다.
- 모든 측정에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0` 으로 정합성 불변식은 계속 유지됐다.
- 결론적으로 현재 로컬 환경과 현재 workload 에서는 `sub-1.0` 구간에서도 재현성 있게 안정적인 factor 를 아직 확보하지 못했다.

## 7차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `pool=28` 기준 `sub-1.0` 하위 구간 반복 탐색 5회
- stage factor `0.5 / 0.6 / 0.7`
- mixed scenario를 포함한 게시판 목록/핫 게시물/검색/게시물 작성/댓글 작성 혼합 부하
- 멱등 좋아요 등록/해제 경쟁
- 북마크 등록/해제 혼합 경쟁
- 팔로우/언팔로우 혼합 경쟁
- 차단/해제 혼합 경쟁
- 알림 생성 vs 전체 읽음 혼합 경쟁
- `postgresSnapshot` 기반 PostgreSQL wait/slow-query 원인 수집

핵심 결론:

- [sub-stability-20260324-064643.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-064643.md) 기준 `highest stable factor` 는 `0.6` 이었다.
- `0.5` 는 `5/5 PASS`, `0.6` 도 `5/5 PASS` 였다.
- `0.7` 은 `3/5 PASS`, `1/5 LIMIT`, `1/5 FAIL` 이었다.
- `0.7` LIMIT/FAIL 런에서도 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0` 으로 정합성 불변식은 계속 유지됐다.
- `0.7` 에서 관측된 실패 신호는 `dbPoolTimeouts=1~3`, `unexpected_response_rate` 발생, `p99` 최대 `685.37ms` 였다.
- focused `0.7` wait 수집 런 [soak-20260324-070318.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-070318.md) 기준 런 중 피크는 `activeConnections=28`, `threadsAwaitingConnection=177~185`, `waitingSessions` 최대 `12`, `lockWaitingSessions` 최대 `8` 이었다.
- 같은 런의 `postgresSnapshot` 에서는 `Lock/transactionid`, `Lock/tuple`, `LWLock/WALWrite`, `IO/WALSync` wait 가 관측됐고, `slowActiveQueries` 는 주로 `posts + users` 목록 조회와 `count(*)` 검색/목록 count 쿼리였다.
- 결론적으로 현재 로컬 환경에서 soak 기준 안정 factor 는 `0.6` 이고, `0.7` 부근부터는 정합성 깨짐이 아니라 read pressure + lock/WAL pressure 로 인해 pool 대기와 응답 지연이 먼저 증가한다.

## 8차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `post.list` / 검색 `count(*)` read path 최적화
- 목록/검색 projection 기반 본문 select 전환
- `Page countQuery` 명시로 `users/tags` join fan-out 완화
- `0.7` focused soak 재측정 2회

핵심 결론:

- 목록/검색 본문은 이제 `author` 전체 엔티티가 아니라 `username` 중심 projection 을 사용한다.
- 검색/태그 `count(*)` 는 더 이상 `join users` / `join post_tags` fan-out 을 그대로 타지 않고, 명시적 `countQuery` 로 분리됐다.
- targeted 테스트 [PostListOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostListOptimizationIntegrationTest.java) 기준 latest list 는 `<=4` statement, `board + keyword search` 는 `<=5` statement 상한을 통과했다.
- 수정 후 focused `0.7` 재측정 [soak-20260324-072247.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-072247.md) 에서는 `p95=763.21ms`, `p99=980.04ms`, `dbPoolTimeouts=10`, `unexpected_response_rate=0.0003` 이었다.
- 정합성은 계속 유지됐다. `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0`
- 다만 macro latency 기준으로는 아직 `0.7` 안정화에 성공하지 못했다. 쿼리 모양은 좋아졌지만 pool 대기와 timeout 은 여전히 남아 있다.
- 같은 재측정의 `slowActiveQueries` 는 여전히 목록 본문 select 와 `count(p1_0.id)` 검색/태그 count 쿼리를 가리켰다.
- 결론적으로 이번 변경은 `read query shape 개선`에는 성공했지만, 현재 로컬 환경에서 `0.7` 안정 구간을 확보할 정도의 체감 개선까지는 이어지지 않았다.

## 9차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- 최신 코드 기준 `0.6 / 30분` soak 재검증
- mixed scenario 전체 장시간 검증
- PostgreSQL `wait_event` 타임라인 수집
- `EXPLAIN ANALYZE` 기반 목록/검색/태그 count query 실행계획 분석

핵심 결론:

- 새 코드 기준 장시간 soak [soak-20260324-073142.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-073142.md) 는 `FAIL` 이었다.
- `http p95=979.75ms`, `p99=1289.36ms`, `unexpected_response_rate=0.0036`, `dbPoolTimeouts=4752`, `maxActiveConnections=28`, `maxThreadsAwaitingConnection=194` 가 관측됐다.
- 같은 soak 의 SQL 스냅샷 [soak-20260324-073142-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260324-073142-sql.tsv) 기준 `duplicate row=0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- soak 타임라인 기준 `postgresSnapshot` 최고치는 `waitingSessions=9`, `lockWaitingSessions=7`, `longestQueryMs=154`, `longestTransactionMs=225` 였다.
- 가장 자주 관측된 wait 는 `Lock/transactionid`, `Lock/tuple`, `IO/WALSync`, `LWLock/WALWrite` 였다.
- 가장 자주 관측된 느린 query 는 목록 본문 `post.list`, `board + keyword count(*)`, `tag count(*)` 였다.
- 결론적으로 현재 로컬 환경과 최신 코드 기준으로는 `0.6` 도 더 이상 안정 factor 가 아니며, 먼저 깨지는 것은 정합성이 아니라 DB pool 포화와 read pressure 다.

## 10차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- PostgreSQL 검색 query shape rewrite 이후 `EXPLAIN ANALYZE` 재측정
- 검색 집중 soak 재측정
- `sub-1.0` 반복 ramp-up 재측정 5회
- `FTS branch + username trigram branch + UNION/ID dedup` 구조의 실효성 검증

핵심 결론:

- 새 query shape 기준 `EXPLAIN ANALYZE` 에서 `idx_posts_search_fts`, `idx_users_username_trgm`, `idx_post_tags_tag_post` 가 실제 실행계획에 사용됐다.
- [board-keyword-list.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-rewrite/board-keyword-list.txt) 기준 `board + keyword list` 는 `14.852ms`, [board-keyword-count.txt](/home/admin0/effective-disco/loadtest/results/explain-20260324-rewrite/board-keyword-count.txt) 기준 `board + keyword count(*)` 는 `6.334ms` 였다.
- 검색 집중 soak [soak-20260324-101110.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-101110.md) 는 전체 status 는 `FAIL` 이었지만, `http p95=437.57ms`, `p99=639.39ms`, `unexpected_response_rate=0.0001`, `dbPoolTimeouts=1` 로 이전 검색 집중 측정 대비 크게 개선됐다.
- 같은 검색 집중 측정의 최종 서버 스냅샷 [soak-20260324-101110-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-101110-server.json) 기준 `duplicateKeyConflicts=0`, `relationDuplicateRows=0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- 반복 ramp-up [sub-stability-20260324-101158.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-101158.md) 기준 `0.6`, `0.7`, `0.8` 이 모두 `5/5 PASS` 였고, `highest stable factor = 0.8` 이었다.
- 반복 ramp-up 전 런에서 `dbPoolTimeouts=0`, `unexpected_response_rate=0.0000`, 관계 중복 row `0`, SQL mismatch `0` 이었다.
- 결론적으로 이번 검색 query shape rewrite 는 정합성을 해치지 않으면서, 검색 병목을 실제로 줄여 안정 factor 를 `n/a` 수준에서 `0.8` 까지 회복시켰다.

## 11차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- 최신 검색 query shape 기준 `0.8 / 30분` soak
- mixed scenario 전체 장시간 검증
- PostgreSQL `wait_event` / `slowActiveQueries` 장시간 추적
- 반복 ramp-up 안정 구간과 장시간 soak 안정 구간의 차이 검증

핵심 결론:

- [soak-20260324-104517.md](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517.md) 기준 `0.8 / 30분` soak 는 `FAIL` 이었다.
- `http p95=1236.62ms`, `p99=1721.70ms`, `unexpected_response_rate=0.0031`, `dbPoolTimeouts=4707`, `maxActiveConnections=28`, `maxThreadsAwaitingConnection=200` 이 관측됐다.
- 같은 soak 의 SQL 스냅샷 [soak-20260324-104517-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517-sql.tsv) 기준 `duplicate row=0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- 최종 서버 스냅샷 [soak-20260324-104517-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517-server.json) 기준 남은 대표 병목은 `post.list averageSqlExecutionTimeMs=61.76`, `averageWallTimeMs=65.84` 였다.
- 같은 스냅샷에서 `notification.read-all.summary averageWallTimeMs=58.81`, `notification.store averageWallTimeMs=14.68`, `jwt.auth.load-user.db averageWallTimeMs=222.12` 까지 커졌다.
- 장시간 타임라인 [soak-20260324-104517-metrics.jsonl](/home/admin0/effective-disco/loadtest/results/soak-20260324-104517-metrics.jsonl) 에서는 `WALWrite`, `WALSync`, `transactionid`, `tuple` wait 가 반복적으로 관측됐다.
- 결론적으로 `0.8` 은 반복 ramp-up 기준 안정 구간이지만, `30분 soak` 기준 안정 구간은 아니다. 현재 시스템은 정합성은 유지하지만 장시간 mixed load 에서는 여전히 DB pool 포화와 읽기 경로 점유 시간 때문에 무너진다.

## 12차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- clean `loadtest` 인스턴스(`18081`)에서 `0.7 / 0.8` 반복 ramp-up 재측정
- `RUNS=5`, `STOP_ON_HTTP_P99_MS=800`, `STOP_ON_K6_THRESHOLD=0`
- 최신 `post.list row-width 축소 + notification read path` 변경 이후 재현성 검증

핵심 결론:

- [sub-stability-20260324-120211.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-120211.md) 기준 `highest stable factor` 는 다시 `n/a` 였다.
- `0.7` 은 `2/5 PASS`, `3/5 LIMIT` 이었다.
- `0.8` 은 도달한 `2회` 모두 `PASS` 가 아니었고, `1/2 LIMIT`, `1/2 FAIL` 이었다.
- `0.7` 의 최대 `p99` 는 `599.81ms`, `dbPoolTimeouts` 최대치는 `1` 이었다.
- `0.8` 의 최대 `p99` 는 `843.34ms`, `dbPoolTimeouts` 최대치는 `3` 이었다.
- 전 런에서 [sub-stability-20260324-120211.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-120211.tsv) 기준 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- 이번 실패 원인은 정합성 깨짐이 아니라 `db-pool-timeout` 과 `unexpected-response` 였다.
- 결론적으로 clean short soak 에서 `dbPoolTimeouts=0` 이 확인됐더라도, 같은 최신 코드 기준 반복 ramp-up 재현성까지 확보된 것은 아니다. 현재 로컬 환경에서 `0.7` 도 아직 안정 구간이라고 볼 수 없다.

## 13차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- clean `loadtest` 인스턴스(`18081`)에서 `0.6 / 0.65 / 0.7` 반복 ramp-up 재측정
- `RUNS=5`, `STOP_ON_HTTP_P99_MS=800`, `STOP_ON_K6_THRESHOLD=0`
- soak 기준 factor 를 보수적으로 다시 찾기 위한 하위 구간 탐색

핵심 결론:

- [sub-stability-20260324-122527.md](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-122527.md) 기준 `highest stable factor` 는 여전히 `n/a` 였다.
- `0.6` 은 `3/5 PASS`, `2/5 LIMIT` 이었다.
- `0.65` 는 도달한 `3회` 중 `2/3 PASS`, `1/3 LIMIT` 이었다.
- `0.7` 은 도달한 `2회` 모두 `PASS` 가 아니었고, `2/2 LIMIT` 이었다.
- `0.6` 의 최대 `p99` 는 `584.11ms`, `dbPoolTimeouts` 최대치는 `1` 이었다.
- `0.65` 의 최대 `p99` 는 `667.72ms`, `dbPoolTimeouts` 최대치는 `1` 이었다.
- `0.7` 의 최대 `p99` 는 `626.59ms`, `dbPoolTimeouts` 최대치는 `1` 이었다.
- 전 런에서 [sub-stability-20260324-122527.tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260324-122527.tsv) 기준 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- 이번 하위 구간 탐색에서는 `unexpected-response` 없이 모두 `db-pool-timeout` 만 LIMIT 신호로 나타났다.
- 결론적으로 현재 로컬 환경에서는 `0.6` 조차 `5/5 PASS` 재현성을 확보하지 못했다. 즉 지금 시점에는 soak 기준 factor 를 아직 확정할 수 없다.

## 14차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- scenario profile 분해 러너 추가
- clean `loadtest` 인스턴스(`18081`)에서 `0.5 / 0.55 / 0.6`, `RUNS=5`
- `browse_search`, `write`, `relation_mixed`, `notification` 개별 반복 ramp-up 재측정

핵심 결론:

- broad mixed 부하에서는 `0.6` 도 안정 구간이 아니었지만, 이번 분해 측정에서는 모든 단일 profile 이 `0.6` 까지 `5/5 PASS` 였다.
- matrix 요약은 [scenario-matrix-20260324-130059.md](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-130059.md), 원본 집계는 [scenario-matrix-20260324-130059.tsv](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-130059.tsv) 에 있다.
- `browse_search`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- `write`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- `relation_mixed`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- `notification`: `0.5 / 0.55 / 0.6` 모두 `5/5 PASS`, `highest stable factor = 0.6`
- 개별 aggregate는 [browse_search aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search-20260324-130059/scenario-browse_search-20260324-130059/sub-stability-20260324-130059-aggregate.tsv), [write aggregate](/home/admin0/effective-disco/loadtest/results/scenario-write-20260324-130059/scenario-write-20260324-131251/sub-stability-20260324-131251-aggregate.tsv), [relation_mixed aggregate](/home/admin0/effective-disco/loadtest/results/scenario-relation_mixed-20260324-130059/scenario-relation_mixed-20260324-132825/sub-stability-20260324-132825-aggregate.tsv), [notification aggregate](/home/admin0/effective-disco/loadtest/results/scenario-notification-20260324-130059/scenario-notification-20260324-134015/sub-stability-20260324-134015-aggregate.tsv) 에 남겼다.
- 모든 개별 profile 에서 `dbPoolTimeouts=0`, `unexpected_response_rate=0`, 관계 중복 `0`, `postLike/comment/unread mismatch=0` 이었다.
- 결론적으로 현재 재현되는 불안정성은 단일 시나리오 자체의 한계가 아니라, `read + write + relation/notification` 이 동시에 섞일 때 발생하는 상호작용 문제다.

## 15차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- 2-profile 조합 지원 추가
- clean `loadtest` 인스턴스(`18081`)에서 `0.5 / 0.55 / 0.6`, `RUNS=5`
- `browse_search+relation_mixed`, `browse_search+notification`, `write+relation_mixed` 반복 ramp-up 비교

핵심 결론:

- 3개 조합 중 `browse_search+relation_mixed` 만 broad mixed 와 유사한 불안정성을 재현했다.
- matrix 요약은 [scenario-matrix-20260324-140610.md](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-140610.md), 원본 집계는 [scenario-matrix-20260324-140610.tsv](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260324-140610.tsv) 에 있다.
- `browse_search+relation_mixed`: `highest stable factor = 0.5`
- 같은 조합의 [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search+relation_mixed-20260324-140610/scenario-browse_search+relation_mixed-20260324-140610/sub-stability-20260324-140610-aggregate.tsv) 기준 `0.55 = 4P/1L`, `0.6 = 1P/0L/3F`, `max p99 = 1126.48ms`, `max dbPoolTimeouts = 247`
- `browse_search+notification`: [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search+notification-20260324-140610/scenario-browse_search+notification-20260324-141718/sub-stability-20260324-141718-aggregate.tsv) 기준 `0.6` 까지 `5/5 PASS`
- `write+relation_mixed`: [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-write+relation_mixed-20260324-140610/scenario-write+relation_mixed-20260324-142909/sub-stability-20260324-142909-aggregate.tsv) 기준 `0.6` 까지 `5/5 PASS`
- 실패한 `browse_search+relation_mixed` 상세는 [sub-stability-20260324-140610.md](/home/admin0/effective-disco/loadtest/results/scenario-browse_search+relation_mixed-20260324-140610/scenario-browse_search+relation_mixed-20260324-140610/sub-stability-20260324-140610.md) 와 [sub-stability-20260324-140610.tsv](/home/admin0/effective-disco/loadtest/results/scenario-browse_search+relation_mixed-20260324-140610/scenario-browse_search+relation_mixed-20260324-140610/sub-stability-20260324-140610.tsv) 에 있다.
- 전 조합에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- 결론적으로 broad mixed 의 최소 재현 조합 후보는 `browse_search + relation_mixed` 다. 즉 현재 병목은 단순 read 나 단순 relation write 가 아니라, 두 경로가 동시에 DB pool 과 트랜잭션 점유 시간을 밀어 올릴 때 나타난다.

## 16차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `browse_search` 와 `relation_mixed` 를 더 작은 component로 분해
- 새 component: `browse_board_feed`, `hot_post_details`, `search_catalog`, `like_mixed`, `bookmark_mixed`, `follow_mixed`, `block_mixed`
- clean `loadtest` 인스턴스(`18081`)에서 `0.5 / 0.55 / 0.6`, `RUNS=5`
- `browse_board_feed+relation_mixed`, `search_catalog+relation_mixed`, `browse_board_feed+search_catalog+relation_mixed` 반복 측정

핵심 결론:

- 이번 결과는 `원인 확정`이 아니라 broad mixed 불안정성의 `최소 재현 조건`을 더 좁힌 것이다.
- `browse_board_feed+relation_mixed`: [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+relation_mixed-20260324-174416/scenario-browse_board_feed+relation_mixed-20260324-174416/sub-stability-20260324-174416-aggregate.tsv) 기준 `0.6` 까지 대체로 안정적이었다. 단, `0.5` 에서 `1 FAIL` 이 있어 완전히 무관하다고 단정할 수는 없다.
- `search_catalog+relation_mixed`: [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-search_catalog+relation_mixed-20260324-180052/sub-stability-20260324-180052-aggregate.tsv) 기준 `0.5 / 0.55 / 0.6` 모두 `5/5 PASS` 였다.
- 반면 `browse_board_feed+search_catalog+relation_mixed`: [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/sub-stability-20260324-181449-aggregate.tsv) 기준 `0.5 = 1P/1L/3F`, `0.55 = 0P/0L/1F`, `highest stable factor = n/a` 로 강하게 재현됐다.
- 같은 tri-profile 상세는 [sub-stability-20260324-181449.md](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/scenario-browse_board_feed+search_catalog+relation_mixed-20260324-181449/sub-stability-20260324-181449.md) 에 있다.
- `browse_board_feed+search_catalog+relation_mixed` 의 max `p99` 는 `741.28ms`, max `dbPoolTimeouts` 는 `30` 이었고, `unexpected-response` 도 재현됐다.
- 전 조합에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.
- 결론적으로 현재까지 가장 강한 최소 재현 조건은 `browse_board_feed + search_catalog + relation_mixed` 이다. 즉 read path 하나가 아니라 `feed + search` 가 함께 DB를 누르는 동안 relation write 가 겹칠 때 불안정성이 나타난다.

## 1차에서 보장한 불변식

### 관계형 쓰기 경로

- `post_like` 는 동일 `(post, user)` 조합에 대해 최종 row 수가 항상 `0 또는 1`
- `follow` 는 동일 `(follower, following)` 조합에 대해 최종 row 수가 항상 `0 또는 1`
- `bookmark` 는 동일 `(user, post)` 조합에 대해 최종 row 수가 항상 `0 또는 1`
- `block` 는 동일 `(blocker, blocked)` 조합에 대해 최종 row 수가 항상 `0 또는 1`

### 카운터

- `Post.likeCount == post_like row 수`
- `User.unreadNotificationCount == Notification.isRead = false row 수`
- `likeCount` 는 음수가 되지 않음

### 가입

- 동일 `username/email` 동시 가입 경쟁에서 계정 row는 1개만 남음
- 나머지 경쟁 요청은 예외로 흡수되어 중복 계정을 만들지 않음

## 1차에서 반영한 구현 기준

### 1. 멱등 write + 요청 주체 잠금

- 좋아요/팔로우/북마크/차단은 `toggle` 이 아니라 상태 보장형 연산으로 처리
- 요청 주체 `User` 행을 `PESSIMISTIC_WRITE` 로 잠가 같은 사용자의 경쟁 요청을 직렬화
- 해제는 `delete count` 로 실제 상태 변경 여부를 판단

의도:

- 중복 insert race 방지
- 재시도 시 상태가 뒤집히는 문제 방지
- 카운터와 관계 row 불일치 방지

### 2. AFTER_COMMIT 알림 + 별도 트랜잭션

- 알림은 본문 트랜잭션 안에서 직접 저장하지 않고 이벤트로 분리
- AFTER_COMMIT 리스너에서 `REQUIRES_NEW` 트랜잭션으로 저장

의도:

- 롤백된 본문 트랜잭션이 알림/SSE를 남기지 않게 하기 위함

### 3. unread counter 직렬화

- 알림 생성과 전체 읽음 처리는 모두 수신자 `User` 행 잠금 기반으로 수행
- 이로써 `알림 생성` 과 `mark all read` 가 엇갈려도 counter drift를 막음

의도:

- `incrementUnreadNotificationCount()` 와 `resetUnreadNotificationCount()` 경쟁 시
  counter가 실제 unread row 수와 어긋나는 문제 방지

## 1차 검증 항목

### 쓰기 경로 경쟁 테스트

- 같은 `like` 요청 동시 8회
- 같은 `unlike` 요청 동시 8회
- `like` 와 `unlike` 혼합 경쟁
- 같은 `follow` 요청 동시 8회
- `follow` 와 `unfollow` 혼합 경쟁
- 같은 `bookmark` 요청 동시 8회
- `bookmark` 와 `unbookmark` 혼합 경쟁
- 같은 `block` 요청 동시 8회
- `block` 와 `unblock` 혼합 경쟁

결과:

- 모두 통과

### 알림 정합성 테스트

- 커밋된 트랜잭션만 AFTER_COMMIT 알림 생성
- 롤백된 트랜잭션은 알림/카운터 미생성
- 동시 알림 생성 시 알림 row 수와 unread counter 일치
- `알림 생성` 과 `전체 읽음 처리` 경쟁 후에도 unread counter 와 unread row 수 일치

결과:

- 모두 통과

### 가입 경합 테스트

- 동일 username/email 동시 가입 8회

결과:

- 성공 1회, 실패 7회
- 최종 계정 row 수 1개

## 2차에서 추가 확인한 불변식

### 스트레스 상황의 멱등 관계 쓰기

- 같은 사용자의 `like` 등록 경쟁 중에도 동일 `(post, user)` 조합에 대한 중복 관계 row가 생기지 않음
- 같은 사용자의 `like` 해제 경쟁 중에도 `likeCount` 가 음수가 되지 않음
- 고부하 읽기/쓰기 혼합 상황에서도 애플리케이션은 5xx 나 예상 밖 응답률 증가 없이 요청을 처리함

의도:

- 짧은 단위 테스트를 넘어 실제 혼합 부하에서도 1차에서 정의한 정합성 불변식이 유지되는지 확인
- 정합성은 유지하되, 현재 운영 설정에서 DB pool 병목이 언제 드러나는지 함께 기록

## 3차에서 추가 확인한 불변식

### 반복 경계점 상황의 정합성 유지

- `unexpected_response_rate` 와 `dbPoolTimeouts` 가 발생한 경계 구간에서도 `duplicateKeyConflicts=0`
- 경계 구간에서도 `post_likes`, `bookmarks`, `follows`, `blocks` 의 관계 중복 row `0`
- 경계 구간에서도 `Post.likeCount`, `Post.commentCount`, `User.unreadNotificationCount` 와 실제 row 수 mismatch `0`

의도:

- 한계점 근처에서 먼저 깨지는 것이 정합성인지, 아니면 리소스 포화인지 구분
- 현재 경계점은 쓰기 정합성 붕괴가 아니라 DB pool 포화에서 시작된다는 근거 확보

## 실행 기록

동시성 집중 검증:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon \
  --tests "com.effectivedisco.service.WritePathConcurrencyTest" \
  --tests "com.effectivedisco.service.NotificationAfterCommitIntegrationTest" \
  --tests "com.effectivedisco.service.NotificationServiceTest" \
  --tests "com.effectivedisco.service.AuthServiceConcurrencyTest"
```

전체 회귀:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon
```

결과:

- 동시성 집중 검증 통과
- 전체 테스트 통과

스트레스 부하 검증:

```bash
TS=$(date +%Y%m%d-%H%M%S)
K6_FILE="loadtest/results/k6-stress-summary-$TS.json"
SERVER_FILE="loadtest/results/k6-stress-server-metrics-$TS.json"

curl -fsS -X POST http://localhost:18080/internal/load-test/reset >/dev/null

BASE_URL=http://localhost:18080 \
BROWSE_RATE=80 BROWSE_DURATION=45s BROWSE_PRE_ALLOCATED_VUS=40 BROWSE_MAX_VUS=120 \
HOT_POST_RATE=120 HOT_POST_DURATION=45s HOT_POST_PRE_ALLOCATED_VUS=50 HOT_POST_MAX_VUS=150 \
SEARCH_RATE=40 SEARCH_DURATION=45s SEARCH_PRE_ALLOCATED_VUS=20 SEARCH_MAX_VUS=80 \
WRITE_START_RATE=8 WRITE_STAGE_ONE_RATE=20 WRITE_STAGE_ONE_DURATION=30s WRITE_STAGE_TWO_RATE=35 WRITE_STAGE_TWO_DURATION=30s WRITE_PRE_ALLOCATED_VUS=20 WRITE_MAX_VUS=80 \
LIKE_ADD_VUS=80 LIKE_ADD_DURATION=45s \
LIKE_REMOVE_VUS=80 LIKE_REMOVE_DURATION=45s \
k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" \
  --summary-export "$K6_FILE" \
  loadtest/k6/bbs-load.js

curl -fsS http://localhost:18080/internal/load-test/metrics > "$SERVER_FILE"
```

결과:

- 전체 `http_req_duration`: `p95=147.37ms`, `p99=185.97ms`, `max=418.01ms`
- `browse_board_feed`: `p95=94.20ms`, `p99=115.92ms`
- `hot_post_details`: `p95=90.51ms`, `p99=115.75ms`
- `search_catalog`: `p95=93.17ms`, `p99=118.31ms`
- `create_post`: `p95=135.72ms`, `p99=171.95ms`
- `create_comment`: `p95=187.64ms`, `p99=230.19ms`
- `like_idempotent_add_race`: `p95=149.56ms`, `p99=188.64ms`
- `like_idempotent_remove_race`: `p95=148.41ms`, `p99=186.11ms`
- 총 `86,840` HTTP 요청, `81,918` iterations, peak `191` VUs
- `unexpected_response_rate=0.0000`
- 서버 계측: `duplicateKeyConflicts=0`, `dbPoolTimeouts=0`, `maxActiveConnections=20`, `maxThreadsAwaitingConnection=176`
- 결과 파일:
  [k6-stress-summary-20260324-013824.json](/home/admin0/effective-disco/loadtest/results/k6-stress-summary-20260324-013824.json)
  [k6-stress-server-metrics-20260324-013824.json](/home/admin0/effective-disco/loadtest/results/k6-stress-server-metrics-20260324-013824.json)

반복 경계점 검증:

```bash
for run in 1 2 3 4 5
do
  STOP_ON_K6_THRESHOLD=0 \
  STOP_ON_HTTP_P99_MS=800 \
  STAGE_FACTORS=1,1.25,1.5,1.75,2,2.25,2.5,3,3.5,4 \
  BROWSE_DURATION=12s HOT_POST_DURATION=12s SEARCH_DURATION=12s \
  WRITE_STAGE_ONE_DURATION=8s WRITE_STAGE_TWO_DURATION=8s \
  LIKE_ADD_DURATION=12s LIKE_REMOVE_DURATION=12s \
  BOOKMARK_MIXED_DURATION=12s FOLLOW_MIXED_DURATION=12s \
  BLOCK_MIXED_DURATION=12s NOTIFICATION_MIXED_DURATION=12s \
  BASE_URL=http://localhost:18080 \
  ./loadtest/run-bbs-ramp-up.sh
done
```

결과:

- 반복 결과 디렉터리:
  `loadtest/results/boundary-repeat-20260324-022932`
- `1.0x`: `5/5 PASS`, 전체 `p99=409.75ms~443.98ms`
- `1.25x`: `5/5 PASS`, 전체 `p99=533.37ms~559.26ms`
- `1.5x`: `3/5 PASS`, `2/5 FAIL`
- `1.75x`: 도달한 `3/3` 모두 `LIMIT/FAIL`
- `1.5x` 실패 런:
  `unexpected_response_rate=0.0006~0.0021`, `dbPoolTimeouts=8~46`, 전체 `p99=714.22ms~1270.79ms`
- `1.75x` 실패/제한 런:
  `unexpected_response_rate=0.0000~0.0049`, `dbPoolTimeouts=0~85`, 전체 `p99=925.58ms~1075.05ms`
- 전체 반복 런에서 `maxActiveConnections=20`, `maxThreadsAwaitingConnection=190~200`
- 전체 반복 런에서 `duplicateKeyConflicts=0`, 관계 중복 row `0`, SQL mismatch `0`

## 아직 남은 리스크

### 분산 환경

- 현재 검증은 단일 DB + 단일 애플리케이션 프로세스 기준
- 멀티 인스턴스 환경의 SSE fan-out, 로컬 메모리 상태 일관성은 별도 검토 필요

### 장시간/대규모 스트레스

- 2차 스트레스 런으로 단기 혼합 부하 검증은 완료
- 3차 반복 ramp-up 으로 경계점은 `1.5x~1.75x` 구간으로 좁혔음
- 다만 장시간 soak test 에서 counter drift, connection leak, slow creep, deadlock 여부는 아직 별도 확인이 필요
- 현재는 "어디서 깨지는가"만 찾았고 "왜 그 지점에서 깨지는가"는 쿼리/트랜잭션 분석이 남아 있음

### 대용량 데이터

- 이번 1차는 정합성 중심이며 대규모 데이터셋에서의 쿼리 플랜/인덱스 효율은 범위 밖

## 다음 갱신 규칙

이 문서를 갱신할 때는 아래 순서를 유지한다.

1. 단계명과 상태를 추가한다.
2. 새로 보장한 불변식을 적는다.
3. 실제 수정한 구현 기준을 적는다.
4. 검증 항목과 실행 명령을 적는다.
5. 남은 리스크를 줄였는지 여부를 업데이트한다.

## 변경 로그

### Phase 1

- 멱등 write 경로 + 요청 주체 잠금 검증 완료
- AFTER_COMMIT 알림 및 unread counter 정합성 검증 완료
- 중복 회원가입 경합 검증 완료

### Phase 2

- 2026-03-24 스트레스 k6 런 완료
- 읽기/쓰기/좋아요 경쟁 혼합 부하에서도 `unexpected_response_rate=0.0000` 확인
- `duplicateKeyConflicts=0`, `dbPoolTimeouts=0` 확인
- DB pool 최대 active `20`, 최대 대기 쓰레드 `176` 으로 포화 징후 확인
