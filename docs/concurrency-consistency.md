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

## 17차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `browse_board_feed + search_catalog + relation_mixed` 를 relation 단위로 재분해
- 우선순위 조합으로 `browse_board_feed+search_catalog+like_mixed` 반복 측정
- clean `loadtest` 인스턴스(`18081`)에서 `0.5 / 0.55 / 0.6`, `RUNS=5`

핵심 결론:

- 이번 결과는 `root cause 확정`이 아니라 `원인 추적 1순위 대상 확정`이다.
- `browse_board_feed+search_catalog+like_mixed`: [suite](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/sub-stability-20260324-211651.md), [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/scenario-browse_board_feed+search_catalog+like_mixed-20260324-211651/sub-stability-20260324-211651-aggregate.tsv) 기준 `0.5 = 5P/0L/0F`, `0.55 = 3P/0L/2F`, `0.6 = 1P/0L/2F` 로 불안정성이 재현됐다.
- 같은 조합의 max `p99` 는 `1056.45ms`, max `dbPoolTimeouts` 는 `48` 이었고, `unexpected-response` 도 함께 발생했다.
- 반면 앞 단계에서 `search_catalog+relation_mixed` 는 `0.6` 까지 `5/5 PASS`, `browse_board_feed+relation_mixed` 도 강한 재현 조합은 아니었다.
- 따라서 relation write 전체가 아니라, 현재 가장 먼저 추적해야 할 경로는 `feed + search + like add/remove` 조합이다.
- `bookmark/follow/block` 쪽은 이번 단계에서 끝까지 돌리지 않았다. `like_mixed` 에서 이미 충분한 재현성이 확인돼 원인 추적 대상을 먼저 확정하는 쪽이 더 효율적이었기 때문이다.
- 이번 `like_mixed` 재현에서도 `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0` 으로 정합성 불변식은 계속 유지됐다.

## 18차 결과

상태: 완료

검증 날짜:

- 2026-03-24

검증 범위:

- `browse_board_feed + search_catalog + like_mixed` 전용 short soak 정밀 계측
- fresh `loadtest` 인스턴스(`18082`)에서 `0.5 baseline` 과 `0.6 reproduction` 비교
- `post.list`, `post.like.add`, `post.like.remove`, PostgreSQL wait snapshot 동시 비교

핵심 결론:

- 이번 결과는 `root cause 확정`이 아니라, `like-focused 최소 재현 조합`에서 무엇이 먼저 커지는지 정밀 계측한 것이다.
- 유효한 실행 artifact:
  - [0.5 report](/home/admin0/effective-disco/loadtest/results/soak-20260324-232807.md)
  - [0.5 server](/home/admin0/effective-disco/loadtest/results/soak-20260324-232807-server.json)
  - [0.6 report](/home/admin0/effective-disco/loadtest/results/soak-20260324-232919.md)
  - [0.6 server](/home/admin0/effective-disco/loadtest/results/soak-20260324-232919-server.json)
- `0.5` 는 `http p95=354.96ms`, `p99=471.57ms`, `dbPoolTimeouts=0`, `maxThreadsAwaitingConnection=91` 이었다.
- `0.6` 은 `http p95=824.89ms`, `p99=1005.76ms`, `unexpected_response_rate=0.0070`, `dbPoolTimeouts=88`, `maxThreadsAwaitingConnection=152` 였다.
- 반면 `post.like.add/remove` 평균 SQL 시간은 같이 폭증하지 않았다.
  - `post.like.add averageSqlExecutionTimeMs = 20.23 -> 12.94`
  - `post.like.remove averageSqlExecutionTimeMs = 20.84 -> 12.82`
  - 대신 max transaction/wall time 은 `~100ms` 수준으로 유지돼, tuple/transactionid lock 과 WAL pressure 를 만드는 write 경로라는 점은 그대로 확인됐다.
- 가장 먼저 크게 늘어난 profile 은 `post.list` 였다.
  - `post.list averageSqlExecutionTimeMs = 147.95 -> 208.62`
  - `post.list averageWallTimeMs = 152.46 -> 213.22`
  - `post.list maxSqlExecutionTimeMs = 511.43 -> 538.58`
- PostgreSQL peak 도 같은 방향이었다.
  - `longestQueryMs = 217 -> 273`
  - `longestTransactionMs = 321 -> 389`
  - wait 는 두 run 모두 `Lock/tuple`, `Lock/transactionid` 가 보였고, `0.6` 에서 `IO/WALSync` 가 추가됐다.
- 정합성 불변식은 계속 유지됐다. `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0`.
- 따라서 현재 1순위 추적 대상은 `비싼 like SQL 자체`가 아니라, `feed/search read pressure 로 커진 post.list` 위에 `like add/remove` 가 lock/WAL pressure 를 얹는 조합이다.

## 19차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- `post.list` 세분화 계측 + `/api/posts/slice` 전환 후 `browse_board_feed + search_catalog + like_mixed` short soak 재측정
- fresh `loadtest` 인스턴스(`18082`)에서 `0.5 baseline` 과 `0.6 reproduction` 비교
- `post.list.browse.rows`, `post.list.search.rows`, `post.list.tag.rows`, `post.like.add/remove` 동시 비교

핵심 결론:

- 유효한 실행 artifact:
  - [0.5 report](/home/admin0/effective-disco/loadtest/results/soak-20260325-055840.md)
  - [0.5 server](/home/admin0/effective-disco/loadtest/results/soak-20260325-055840-server.json)
  - [0.6 report](/home/admin0/effective-disco/loadtest/results/soak-20260325-055919.md)
  - [0.6 server](/home/admin0/effective-disco/loadtest/results/soak-20260325-055919-server.json)
- `0.5` 는 `http p95=134.77ms`, `p99=156.79ms`, `dbPoolTimeouts=0`, `unexpected_response_rate=0.0000` 이었다.
- `0.6` 도 `http p95=169.70ms`, `p99=200.12ms`, `dbPoolTimeouts=0`, `unexpected_response_rate=0.0000` 으로 short soak 기준 안정적이었다.
- 세분화 profile 기준으로 가장 비싼 read path 는 `search rows` 가 아니라 `tag rows` 였다.
  - `post.list.browse.rows averageSqlExecutionTimeMs = 59.49 -> 62.84`
  - `post.list.search.rows averageSqlExecutionTimeMs = 47.90 -> 49.59`
  - `post.list.tag.rows averageSqlExecutionTimeMs = 82.90 -> 85.32`
- `post.like.add/remove` 는 평균 `~18ms` 수준으로 유지됐다.
  - `post.like.add averageSqlExecutionTimeMs = 18.08 -> 17.58`
  - `post.like.remove averageSqlExecutionTimeMs = 17.67 -> 17.48`
- PostgreSQL peak 도 짧은 구간에서는 억제됐다.
  - `longestQueryMs = 84 -> 84`
  - `longestTransactionMs = 93 -> 95`
  - wait 는 여전히 `Lock/tuple`, `Lock/transactionid` 가 보였지만 timeout으로 이어지지 않았다.
- 정합성 불변식은 계속 유지됐다. `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0`.
- 따라서 slice API 전환 후 like-focused 최소 재현 조합은 short soak 기준으로는 사실상 재현이 해소됐고, 남은 read path 후보는 `browse/search` 보다 `tag rows` 쪽이 더 비싼 상태다.

## 20차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- 같은 최신 코드 기준으로 `browse_board_feed + search_catalog + like_mixed` 반복 안정성 재측정
- fresh `loadtest` 인스턴스(`18082`)에서 `RUNS=5`, `STAGE_FACTORS=0.6,0.7`

핵심 결론:

- 실행 artifact:
  - [suite](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260325-060233/sub-stability-20260325-060233.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_board_feed+search_catalog+like_mixed-20260325-060233/sub-stability-20260325-060233-aggregate.tsv)
- `0.6` 은 `5/5 PASS` 였다.
  - max `http p99 = 341.31ms`
  - max `dbPoolTimeouts = 0`
  - max `waiting = 95`
- `0.7` 은 `3/5 PASS`, `2/5 FAIL` 이었다.
  - max `http p99 = 822.23ms`
  - max `dbPoolTimeouts = 6`
  - max `waiting = 156`
- `highest stable factor = 0.6`
- 실패한 `0.7` run 들도 정합성 불변식은 유지됐다.
  - `duplicateKeyConflicts = 0`
  - 관계 중복 row = `0`
  - `postLike/comment/unread mismatch = 0`
- 따라서 최신 `slice API + post.list 세분화` 기준으로 like-focused 최소 재현 조합의 현재 기준선은 `0.6 안정 / 0.7 불안정` 이다.

## 21차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- 최신 코드 기준 broad mixed 반복 안정성 재측정
- fresh `loadtest` 인스턴스(`18082`)에서 `RUNS=5`, `STAGE_FACTORS=0.6,0.7`
- 전체 mixed 시나리오가 최신 `slice API + post.list 세분화` 이후에도 실제로 안정화됐는지 재확인

핵심 결론:

- 실행 artifact:
  - [suite](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-061955.md)
  - [tsv](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-061955.tsv)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-061955-aggregate.tsv)
- broad mixed 는 아직 안정 구간을 확보하지 못했다.
  - `0.6 = 1 PASS / 1 LIMIT / 3 FAIL`
  - `0.7 = 0 PASS / 1 LIMIT / 0 FAIL`
  - `highest stable factor = n/a`
- `0.6` 최악 run 에서는 `http p99 = 1391.67ms`, `dbPoolTimeouts = 623`, `maxThreadsAwaitingConnection = 192` 가 나왔다.
- `0.7` 에 도달한 1개 run 도 `db-pool-timeout` 으로 제한됐다.
  - `http p99 = 777.62ms`
  - `dbPoolTimeouts = 2`
  - `maxThreadsAwaitingConnection = 187`
- broad mixed 실패에서도 정합성 불변식은 계속 유지됐다.
  - `duplicateKeyConflicts = 0`
  - 관계 중복 row = `0`
  - `postLike/comment/unread mismatch = 0`
- 따라서 like-focused 조합의 개선이 broad mixed 전체 안정화로 곧바로 이어지지는 않았고, 남은 문제는 여전히 `cross-profile interaction` 이다.

## 22차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- 최신 코드 기준 scenario matrix 재실행
- fresh `loadtest` 인스턴스(`18081`)에서 `RUNS=5`, `STAGE_FACTORS=0.5,0.55,0.6`
- 단일 profile 기준으로 어느 경로까지 안정적인지 다시 확정

핵심 결론:

- 실행 artifact:
  - [summary](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260325-062843.md)
  - [summary tsv](/home/admin0/effective-disco/loadtest/results/scenario-matrix-20260325-062843.tsv)
- 최신 코드 기준 단일 profile 은 모두 `0.6` 까지 `5/5 PASS` 였다.
  - `browse_search = 0.5/0.55/0.6 모두 5/5 PASS`
  - `write = 0.5/0.55/0.6 모두 5/5 PASS`
  - `relation_mixed = 0.5/0.55/0.6 모두 5/5 PASS`
  - `notification = 0.5/0.55/0.6 모두 5/5 PASS`
- 각 profile 의 `highest stable factor` 는 모두 `0.6` 이었다.
- 따라서 최신 broad mixed 불안정성은 `단일 profile` 자체의 한계가 아니라, `복수 profile 이 동시에 걸릴 때의 상호작용` 으로 다시 좁혀졌다.
- 즉 다음 단계의 관심사는 broad mixed 를 다시 반복하는 것이 아니라, 최신 코드 기준으로 `2-profile / 3-profile` 조합을 다시 좁혀 최소 재현 조건을 갱신하는 것이다.

## 23차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- 최신 코드 기준 `2-profile / 3-profile` 조합 재확정용 조합 matrix 러너 추가
- broad mixed 가 실제로 깨지는 `0.6` 에서 pair screen 우선 실행
- 최소 불안정 크기가 pair 인지 triple 인지 재확정

핵심 결론:

- 실행 artifact:
  - [pair summary](/home/admin0/effective-disco/loadtest/results/scenario-combination-matrix-20260325-075043.md)
  - `browse_search+write`
    - [suite](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+write-20260325-075043/scenario-browse_search+write-20260325-075043/sub-stability-20260325-075043.md)
    - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+write-20260325-075043/scenario-browse_search+write-20260325-075043/sub-stability-20260325-075043-aggregate.tsv)
  - `browse_search+relation_mixed`
    - [suite](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-075043/scenario-browse_search+relation_mixed-20260325-075555/sub-stability-20260325-075555.md)
    - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-075043/scenario-browse_search+relation_mixed-20260325-075555/sub-stability-20260325-075555-aggregate.tsv)
  - `browse_search+notification`
    - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+notification-20260325-075043/scenario-browse_search+notification-20260325-075955/sub-stability-20260325-075955-aggregate.tsv)
- `browse_search+write` 는 `0.6 = 5/5 PASS` 였다.
- `browse_search+notification` 도 `0.6 = 5/5 PASS` 였다.
- 반면 `browse_search+relation_mixed` 는 `0.6 = 0/5 PASS, 5/5 FAIL` 로 강하게 재현됐다.
  - max `http p99 = 962.75ms`
  - max `dbPoolTimeouts = 95`
  - max `waiting = 173`
- 따라서 최신 코드 기준 broad mixed 불안정성의 최소 재현 크기는 다시 `2-profile` 로 확정됐다.
- 이번 러너는 `최소 불안정 크기` 를 찾는 용도이므로, pair 에서 이미 unstable 이 확인되면 triple 로 더 올라가지 않도록 중단했다.
- 즉 현재 1순위 추적 조합은 다시 `browse_search + relation_mixed` 이다.

## 24차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- `search_catalog` 를 `keyword search` 전용으로 축소
- `tag_search`, `sort_catalog` read profile 추가
- `read path × relation write` 전용 pair matrix 러너 추가
- fresh `loadtest` 인스턴스(`18081`)에서 `RUNS=3`, `STAGE_FACTORS=0.6`

핵심 결론:

- 실행 artifact:
  - [pair summary](/home/admin0/effective-disco/loadtest/results/read-relation-pair-matrix-20260325-083639.md)
- `search_catalog` 의 atomic pair는 전부 안정적이었다.
  - `search_catalog+like_mixed = 0.6 3/3 PASS`
  - `search_catalog+bookmark_mixed = 0.6 3/3 PASS`
  - `search_catalog+follow_mixed = 0.6 3/3 PASS`
  - `search_catalog+block_mixed = 0.6 3/3 PASS`
- `tag_search` 의 atomic pair도 전부 안정적이었다.
  - `tag_search+like_mixed = 0.6 3/3 PASS`
  - `tag_search+bookmark_mixed = 0.6 3/3 PASS`
  - `tag_search+follow_mixed = 0.6 3/3 PASS`
  - `tag_search+block_mixed = 0.6 3/3 PASS`
- 예시 artifact:
  - [search_catalog+like_mixed](/home/admin0/effective-disco/loadtest/results/read-relation-search_catalog+like_mixed-20260325-083639/scenario-search_catalog+like_mixed-20260325-083639/sub-stability-20260325-083639.md)
  - [tag_search+like_mixed](/home/admin0/effective-disco/loadtest/results/read-relation-tag_search+like_mixed-20260325-083639/scenario-tag_search+like_mixed-20260325-084608/sub-stability-20260325-084608.md)
- 따라서 최신 코드 기준으로는 `atomic search/tag read + atomic relation write` pair만으로는 `0.6` broad mixed 실패를 재현하지 못했다.
- 이 결과는 최소 재현 조건이 다시 `browse_board_feed + (search/tag/sort) + relation_write` 수준의 더 큰 read pressure 조합으로 남아 있음을 뜻한다.
- 정합성 불변식은 이 pair screen에서도 유지됐다. `duplicateKeyConflicts=0`, 관계 중복 row `0`, `postLike/comment/unread mismatch=0`.

## 25차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- `effectivedisco_loadtest` 전용 DB 를 `DROP/CREATE` 로 다시 비운 뒤 clean baseline 재측정
- single-profile 기준선 재확인
  - `browse_search`
- broad mixed 기준선 재확인
  - `0.6, 0.65, 0.7`, `RUNS=5`
- 기존 최소 재현 pair 재확인
  - `browse_search+write`
  - `browse_search+relation_mixed`

핵심 결론:

- 실행 artifact:
  - clean `browse_search`
    - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-browse_search-20260325-095737/scenario-browse_search-20260325-095737/sub-stability-20260325-095737-aggregate.tsv)
  - clean broad mixed
    - [suite](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-110827.md)
    - [aggregate](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-110827-aggregate.tsv)
  - clean pair
    - `browse_search+write`
      - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+write-20260325-112422/scenario-browse_search+write-20260325-112422/sub-stability-20260325-112422-aggregate.tsv)
    - `browse_search+relation_mixed`
      - [suite](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-112422/scenario-browse_search+relation_mixed-20260325-112937/sub-stability-20260325-112937.md)
      - [aggregate](/home/admin0/effective-disco/loadtest/results/scenario-combination-browse_search+relation_mixed-20260325-112422/scenario-browse_search+relation_mixed-20260325-112937/sub-stability-20260325-112937-aggregate.tsv)
- clean `browse_search` 는 `0.5 / 0.55 / 0.6` 전부 `5/5 PASS` 였다.
  - max `http p99 = 9.77ms / 7.04ms / 7.31ms`
  - `dbPoolTimeouts = 0`
- clean broad mixed 는 `0.6 / 0.65 / 0.7` 전부 `5/5 PASS` 였다.
  - max `http p99 = 209.81ms / 230.70ms / 244.76ms`
  - `dbPoolTimeouts = 0`
  - max `waiting = 186 / 187 / 187`
- 예전 최소 재현 pair 도 clean baseline 에서는 더 이상 깨지지 않았다.
  - `browse_search+write = 0.6 5/5 PASS`
  - `browse_search+relation_mixed = 0.6 5/5 PASS`
    - max `http p99 = 177.88ms`
    - `dbPoolTimeouts = 0`
    - max `waiting = 173`
- 따라서 cleanup/전용 DB 분리 이전에 관측된 broad mixed 불안정성과 pair 재현 결과는, 현재 기준선으로는 유지되지 않는다.
- 지금부터 "현재 baseline" 으로 봐야 할 값은 `전용 loadtest DB + 자동 cleanup` 조합에서 얻은 결과이며, 현재 clean 기준 stable factor 는 적어도 `broad mixed 0.7` 이다.
- 이전 결과는 방향성 참고용으로는 의미가 있지만, stable factor 와 p95/p99 의 현재 기준선으로는 더 이상 사용하지 않는 것이 맞다.

## 26차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- clean 전용 `effectivedisco_loadtest` DB 재생성
- broad mixed 안정 구간 상향 재측정
  - `0.8, 0.85, 0.9`, `RUNS=5`

핵심 결론:

- 실행 artifact:
  - [suite](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-121551.md)
  - [aggregate](/home/admin0/effective-disco/loadtest/results/sub-stability-20260325-121551-aggregate.tsv)
- `0.8 / 0.85 / 0.9` 전부 `5/5 PASS` 였다.
  - `0.8` max `p99 = 262.01ms`
  - `0.85` max `p99 = 276.54ms`
  - `0.9` max `p99 = 277.59ms`
  - 전 factor 에서 `dbPoolTimeouts = 0`
  - max `waiting = 194 / 188 / 188`
- clean baseline 기준 broad mixed `highest stable factor` 는 `0.9` 로 올라갔다.
- 이는 전용 DB 기준선에서 현재 혼합 부하 안정성이 예전 문서보다 훨씬 높다는 뜻이다.

## 27차 결과

상태: 완료

검증 날짜:

- 2026-03-25

검증 범위:

- clean 전용 `effectivedisco_loadtest` DB 재생성
- broad mixed `0.9 / 30분 soak`
  - `WARMUP=2m`
  - `SOAK=30m`

핵심 결론:

- 실행 artifact:
  - [suite](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923.md)
  - [server metrics](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923-server.json)
  - [metrics timeline](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923-metrics.jsonl)
  - [sql snapshot](/home/admin0/effective-disco/loadtest/results/soak-20260325-125923-sql.tsv)
- 결과는 `PASS` 였다.
  - `http p95 = 293.97ms`
  - `http p99 = 377.47ms`
  - `unexpected_response_rate = 0.0000`
  - `dbPoolTimeouts = 0`
  - `duplicateKeyConflicts = 0`
  - `maxActiveConnections = 28`
  - `maxThreadsAwaitingConnection = 194`
- SQL 정합성 snapshot 은 전부 `0` 이었다.
  - 관계 중복 row `0`
  - `postLike/comment/unread mismatch = 0`
- 30분 장시간 관찰에서도 정합성, pool timeout, 응답 오류는 발생하지 않았다.
- 다만 장시간 누적 profile 에서는 `notification.read-all.summary`, `notification.store`, `post.list.browse.rows` wall time 이 상대적으로 커졌으므로, 다음 장기 관찰 우선순위는 이 세 경로다.

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

### 측정 기준선 신뢰도

- 2026-03-25 까지 `loadtest` 프로필은 개발 기본 DB와 같은 PostgreSQL 인스턴스를 공유했고, k6 `setup()` / write 시나리오는 실제 row 를 생성했다.
- 이후 [cleanup-loadtest-data.sh](/home/admin0/effective-disco/loadtest/cleanup-loadtest-data.sh) 와 loadtest 전용 DB 분리를 도입해 이 문제를 막았다.
- cleanup/DB 분리 이전의 `p95/p99`, `stable factor`, soak 결과는 방향성 참고용으로는 의미가 있지만, "현재 기준선" 으로는 더 이상 사용하지 않는 것이 맞다.
- 현재 기준선은 clean 전용 DB 에서 다시 측정한 결과다.
  - broad mixed `highest stable factor = 0.9`
  - `0.9 / 30분 soak = PASS`
- 반면 prefix 기반 SQL 정합성 검증과 테스트 기반 동시성 불변식은 데이터 누적의 영향을 상대적으로 덜 받으므로 여전히 참고 가치가 높다.

### 분산 환경

- 현재 검증은 단일 DB + 단일 애플리케이션 프로세스 기준
- 멀티 인스턴스 환경의 SSE fan-out, 로컬 메모리 상태 일관성은 별도 검토 필요

### 장시간/대규모 스트레스

- clean 전용 DB 기준으로 broad mixed `0.9 / 30분 soak` 는 통과했다.
- 이후 clean 전용 DB 기준 `0.9 / 1시간 soak` 는 `dbPoolTimeouts=0`, SQL mismatch `0` 상태를 유지했지만,
  k6 latency threshold 를 넘어서 `FAIL` 이 되었다.
- 따라서 현재 남은 장시간 리스크는 `정합성/timeout` 보다 `장시간 latency drift` 이다.
- 특히 장기 profile 에서 `notification.read-all.summary`, `notification.store`, `post.list.browse.rows` 가 상대적으로 커지고 있으므로, 다음 분석 우선순위는 이 경로들이다.

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

## 28차 결과 - Clean `0.9 / 1시간` Soak 재검증

상태: 완료

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
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 194`
- SQL mismatch = 전부 `0`

### 해석

- 이번 `FAIL` 은 정합성 깨짐이나 pool timeout 때문이 아니라 `k6 latency threshold` 초과 때문이다.
- 즉 clean 전용 DB 기준 현재 시스템은 `0.9 / 1시간` 동안에도 정합성과 pool 안정성은 유지했다.
- 대신 장시간 구간에서 latency drift 가 누적된다.
- 최종 profile 기준 가장 크게 커진 경로는 아래 세 개다.
  - `notification.read-all.summary avgWall = 47.41ms`
  - `notification.store avgWall = 22.17ms`
  - `post.list.browse.rows avgWall = 15.52ms`
- 따라서 다음 최적화 우선순위는 `notification read/write` 와 `browse rows` drift 억제다.

## 2026-03-25 clean `0.9 / 1시간` soak 재측정

상태: 완료, `FAIL`

### 실행 목적

- `notification read/write` 와 `browse rows` drift 완화 변경
  (`898558f`, `Reduce notification lock contention and browse drift`)
  후에도 clean 전용 DB 기준 `0.9 / 1시간 soak` 가 유지되는지 다시 검증한다.
- 특히 이전 baseline
  [soak-20260325-141313.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-141313.md)
  대비 `notification.read-all.summary`, `notification.store`, `post.list.browse.rows`
  가 실제로 내려갔는지 비교한다.

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
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 14967`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 197`
- SQL mismatch = 전부 `0`

### 해석

- 이번 재측정은 이전 clean baseline보다 명확히 나빠졌다.
- 비교 기준:
  - 이전:
    `p95 = 441.09ms`, `p99 = 570.73ms`, `unexpected_response_rate = 0`, `dbPoolTimeouts = 0`
  - 재측정:
    `p95 = 544.10ms`, `p99 = 1221.50ms`, `unexpected_response_rate = 0.0026`, `dbPoolTimeouts = 14967`
- 정합성은 유지됐지만, 성능 기준으로는 이번 변경이 regression 이다.
- 최종 profile 기준 주요 변화:
  - `notification.read-all.summary avgWall = 47.41ms -> 94.13ms`
  - `notification.store avgWall = 22.17ms -> 2.05ms`
  - `post.list.browse.rows avgWall = 15.52ms -> 17.87ms`
- 즉 `notification.store` 는 좋아졌지만, `notification.read-all.summary` 가 거의 두 배로 악화되면서 전체 soak 를 망가뜨렸다.
- 현재 1순위 병목은 `update notifications ... set is_read = true ... id <= cutoff` bulk update 이고,
  browse/search 는 그 다음 순위다.
- 따라서 다음 최적화 우선순위는 `notification read-all` semantics 자체를 줄이는 방향이다.

## 2026-03-25 clean `0.9 / 15분` soak 재측정

상태: 완료, `FAIL`

### 실행 목적

- `1시간 soak` 가 부담스럽기 때문에, 같은 clean 전용 DB 기준에서 `15분`만으로도
  regression 방향이 이미 드러나는지 확인한다.
- 특히 `notification.read-all.summary` 가 초반부터 주요 병목으로 올라오는지 본다.

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
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 4752`
- `maxActiveConnections = 28`
- `maxThreadsAwaitingConnection = 191`
- SQL mismatch = 전부 `0`

### 해석

- `15분` 시점만으로도 이미 `notification.read-all` 경로가 실패 방향을 만든다.
- 최종 profile 기준:
  - `notification.read-all.summary avgWall = 39.80ms`
  - `notification.store avgWall = 1.69ms`
  - `post.list.browse.rows avgWall = 4.71ms`
- 따라서 이 시점의 직접 원인은 browse/search 가 아니라 `notification read-all` 이다.
- 다만 이 결과는 당시 k6 notification 시나리오가 여전히 내부 `read-all` 액션을 반복 호출하던 기준이므로,
  현실 baseline 과 `read-all` worst-case stress 가 한 시나리오에 섞여 있었다.

## 2026-03-25 notification baseline/stress 분리

상태: 구현 완료, 테스트 통과, 재측정 전

### 요지

- 웹 알림 UX
  - `GET /notifications` 는 조회만 수행
  - `POST /notifications/read-page` 는 현재 페이지 batch 만 읽음 처리
- loadtest
  - baseline: `notification_read_write_mixed`
    - 현재 페이지 batch 읽음 + 신규 알림 생성 혼합
  - stress: `notification_read_all_stress`
    - 기존 `read-all` worst-case 유지

### 해석

- 앞으로 baseline soak 는 `현실적인 현재 페이지 읽음` 기준으로 다시 재야 한다.
- 반대로 `read-all` 연타 경로는 broad mixed baseline 이 아니라
  별도 stress 시나리오로만 해석해야 한다.

## 2026-03-25 clean notification baseline/stress short remeasure

상태: 완료

### 실행 목적

- 방금 분리한 두 경로를 같은 clean `effectivedisco_loadtest` DB 조건에서 직접 비교한다.
- baseline 은 `notification` profile(`read-page`)만,
  stress 는 `notification_stress` profile(`read-all`)만 `0.9 / 5분` 실행한다.

### 결과

- baseline suite:
  [soak-20260325-180513.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-180513.md)
- baseline server metrics:
  [soak-20260325-180513-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-180513-server.json)
- baseline sql snapshot:
  [soak-20260325-180513-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-180513-sql.tsv)
- baseline 상태: `FAIL`
- baseline `http p95 = 93.57ms`
- baseline `http p99 = 141.59ms`
- baseline `unexpected_response_rate = 0.0002`
- baseline `dbPoolTimeouts = 35`
- baseline `unreadNotificationMismatchUsers = 1`

- stress suite:
  [soak-20260325-181105.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-181105.md)
- stress server metrics:
  [soak-20260325-181105-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-181105-server.json)
- stress sql snapshot:
  [soak-20260325-181105-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-181105-sql.tsv)
- stress 상태: `PASS`
- stress `http p95 = 121.51ms`
- stress `http p99 = 170.74ms`
- stress `unexpected_response_rate = 0.0000`
- stress `dbPoolTimeouts = 0`
- stress `unreadNotificationMismatchUsers = 0`

### 해석

- 기대와 반대로 `read-all` worst-case stress 보다 `read-page` baseline 이 먼저 깨졌다.
- baseline profile 기준:
  - `notification.read-page.summary avgWall = 28.25ms`
  - `notification.read-page.summary.transition avgWall = 26.92ms`
  - `notification.store avgWall = 9.30ms`
- stress profile 기준:
  - `notification.read-all.summary avgWall = 39.44ms`
  - `notification.read-all.summary.transition avgWall = 38.33ms`
  - `notification.store avgWall = 7.29ms`
- 즉 `read-all` 경로 자체의 절대 비용은 여전히 더 크지만,
  현재 구현의 `read-page` baseline 에서는 짧은 soak 만으로도
  `dbPoolTimeouts` 와 `unread counter drift` 가 재현됐다.
- 따라서 다음 우선순위는 `notification.read-page` 의 정합성과 lock 경합을 직접 파는 것이다.

## 2026-03-25 notification `read-page` 정합성 보강 후 재측정

상태: 완료

### 실행 목적

- `markPageAsRead()` 경로의 unread counter drift 를 먼저 없앤다.
- 같은 clean `effectivedisco_loadtest` DB 조건에서
  baseline(read-page) 과 stress(read-all) 를 다시 짧게 재서
  알림 경로 단독 기준선이 회복됐는지 확인한다.

### 구현 요약

- `notification` mutation 세 경로(`store/read-page/read-all`)를
  같은 recipient 직렬화 규칙 아래로 맞췄다.
- mutation 뒤 unread counter 는 delta 혼합 대신
  실제 unread row 수 refresh 로 통일했다.
- 관련 구현:
  [NotificationService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/NotificationService.java),
  [UserRepository.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/repository/UserRepository.java),
  [NotificationAfterCommitIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationAfterCommitIntegrationTest.java),
  [NotificationServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/NotificationServiceTest.java)

### 결과

- baseline 재측정:
  [soak-20260325-185832.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-185832.md)
- baseline server metrics:
  [soak-20260325-185832-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-185832-server.json)
- baseline 상태: `PASS`
- baseline `dbPoolTimeouts = 0`
- baseline `unreadNotificationMismatchUsers = 0`

- stress 재측정:
  [soak-20260325-190524.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-190524.md)
- stress server metrics:
  [soak-20260325-190524-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-190524-server.json)
- stress sql snapshot:
  [soak-20260325-190524-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-190524-sql.tsv)
- stress 상태: `PASS`
- stress `http p95 = 106.12ms`
- stress `http p99 = 144.66ms`
- stress `unexpected_response_rate = 0.0000`
- stress `dbPoolTimeouts = 0`
- stress `unreadNotificationMismatchUsers = 0`

### 해석

- notification 경로 단독 기준으로는 baseline(read-page) 과 stress(read-all) 모두 다시 안정 상태로 돌아왔다.
- 이 시점부터 broad mixed 재측정은 “알림 경로 자체가 아직 깨진다”가 아니라
  “전체 혼합 경로에 남은 다른 실패 요인이 있는가”를 보는 단계로 바뀌었다.

## 2026-03-25 clean broad mixed `0.9 / 15분` 재측정

상태: 완료

### 실행 목적

- notification 경로 단독 기준선이 회복된 뒤,
  clean broad mixed 에서도 같은 효과가 전파됐는지 다시 확인한다.

### 결과

- suite:
  [soak-20260325-192358.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-192358.md)
- server metrics:
  [soak-20260325-192358-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-192358-server.json)
- sql snapshot:
  [soak-20260325-192358-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-192358-sql.tsv)
- 상태: `FAIL`
- `http p95 = 244.17ms`
- `http p99 = 311.62ms`
- `unexpected_response_rate = 0.0000`
- `dbPoolTimeouts = 0`
- `duplicateKeyConflicts = 1`
- `unreadNotificationMismatchUsers = 0`

### 해석

- 이번 failure 는 더 이상 `notification` drift, `dbPoolTimeouts`, `unread counter mismatch` 때문이 아니다.
- SQL snapshot 기준 relation duplicate row, unread mismatch, comment/like mismatch 는 모두 `0` 이었다.
- 즉 broad mixed 의 남은 실패 원인은 `duplicateKeyConflicts = 1` 로 좁혀졌고,
  다음 추적 대상은 notification 이 아니라 중복 키 예외를 만든 relation write 경로다.

## 2026-03-25 duplicate-key 경로 추적 재측정

상태: 완료

### 실행 목적

- `duplicateKeyConflicts = 1` 이 broad mixed 의 어떤 실제 HTTP 경로에서 나오는지 확인한다.
- 총량 카운터만으로는 후보가 많았기 때문에 request path + constraint 이름 기준으로 다시 좁힌다.

### 구현 요약

- duplicate-key 메트릭을 request signature / constraint 이름별로 저장하도록 확장했다.
- 관련 구현:
  [GlobalExceptionHandler.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/config/GlobalExceptionHandler.java),
  [LoadTestMetricsService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestMetricsService.java),
  [LoadTestMetricsSnapshot.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestMetricsSnapshot.java),
  [LoadTestDuplicateKeyConflictSnapshot.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/loadtest/LoadTestDuplicateKeyConflictSnapshot.java)

### 결과

- suite:
  [soak-20260325-194808.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-194808.md)
- server metrics:
  [soak-20260325-194808-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-194808-server.json)
- sql snapshot:
  [soak-20260325-194808-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-194808-sql.tsv)

- broad mixed 상태: `FAIL`
- `duplicateKeyConflicts = 1`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

- duplicate-key profile:
  - `requestSignature = POST /api/posts`
  - `constraintName = ukt48xdq560gs3gap9g7jg36kgc`
  - sample message: `Key (name)=(write) already exists.`

### 해석

- 남은 duplicate-key 는 relation write 경로가 아니라 게시물 생성 경로였다.
- 충돌 대상은 [Tag.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/domain/Tag.java#L17) 의 `tags.name` 유니크 제약이다.
- 실제 race 지점은 [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java#L784) 의 `resolveTagsForWrite()` 로,
  `findAllByNameIn() -> missing tag saveAll()` 사이에서 동일 태그 `write` 를 동시에 생성하려고 하며 충돌했다.

## 2026-03-25 태그 해석 멱등화 구현

상태: 구현 완료

### 실행 목적

- broad mixed 의 마지막 남은 failure 원인이던 `POST /api/posts` 의
  `tags.name` duplicate-key race 를 제거한다.
- 게시물 작성이 같은 새 태그를 동시에 만들더라도
  게시물 생성 자체는 계속 성공하도록 만든다.

### 구현 요약

- [PostService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/PostService.java)
  - `resolveTagsForWrite()` 가 missing tag 를 직접 `saveAll()` 하지 않도록 변경했다.
  - missing tag 생성 뒤 최종 태그 집합을 다시 조회해 게시물 작성에 사용하도록 바꿨다.
- [TagWriteService.java](/home/admin0/effective-disco/src/main/java/com/effectivedisco/service/TagWriteService.java)
  - missing tag 생성만 짧은 새 트랜잭션으로 분리했다.
  - duplicate-key 충돌은 흡수하고, 최종 재조회에서 이미 생성된 태그를 합치도록 했다.
- 회귀 검증:
  [WritePathConcurrencyTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/WritePathConcurrencyTest.java),
  [PostServiceTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostServiceTest.java),
  [PostCreateOptimizationIntegrationTest.java](/home/admin0/effective-disco/src/test/java/com/effectivedisco/service/PostCreateOptimizationIntegrationTest.java)

### 검증

- same new tag 로 동시 게시물 작성 시:
  - 게시물 `2개` 모두 생성
  - 태그 row 는 `1개`만 생성
- 전체 `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test --no-daemon` 통과

### 해석

- 남아 있던 broad mixed failure 원인은 relation 토글이 아니라
  `resolveTagsForWrite()` 의 태그 생성 race 였고,
  이번 변경으로 그 경로를 멱등 write 로 바꿨다.

## 2026-03-25 clean broad mixed `0.9 / 15분` 재재측정

상태: 완료

### 실행 목적

- 태그 생성 race 수정 후,
  clean broad mixed 기준으로 `duplicateKeyConflicts` 가 실제로 사라졌는지 확인한다.

### 결과

- suite:
  [soak-20260325-203507.md](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507.md)
- server metrics:
  [soak-20260325-203507-server.json](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507-server.json)
- sql snapshot:
  [soak-20260325-203507-sql.tsv](/home/admin0/effective-disco/loadtest/results/soak-20260325-203507-sql.tsv)
- 상태: `PASS`
- `http p95 = 246.53ms`
- `http p99 = 314.19ms`
- `unexpected_response_rate = 0.0000`
- `duplicateKeyConflicts = 0`
- `dbPoolTimeouts = 0`
- `unreadNotificationMismatchUsers = 0`

### 해석

- 태그 생성 race 수정 후 clean broad mixed `0.9 / 15분`은 다시 `PASS`로 회복됐다.
- 현재 기준으로 남아 있는 failure 신호는
  `notification drift`, `duplicate key`, `dbPoolTimeouts` 어느 쪽도 보이지 않았다.
- 즉 clean 전용 DB 기준 broad mixed 의 최신 기준선은
  다시 `0.9 / 15분 = PASS` 이다.
