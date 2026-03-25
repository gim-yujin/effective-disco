# BBS Load Test

Redis/Kafka 없이 애플리케이션 자체 진단 경로와 `k6`만으로 게시판 부하를 측정한다.

## 준비

전용 loadtest DB를 먼저 만든다:

```bash
./loadtest/create-loadtest-db.sh
```

기본값:

- DB 이름: `effectivedisco_loadtest`
- DB 계정: `postgres`
- DB 비밀번호: `4321`

필요하면 `APP_LOAD_TEST_DB_NAME`, `APP_LOAD_TEST_DB_USERNAME`, `APP_LOAD_TEST_DB_PASSWORD` 로 바꿀 수 있다.

그 다음 loadtest 프로필 앱을 실행한다:

```bash
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
```

이제 `loadtest` 프로필은 기본적으로 `effectivedisco_loadtest` DB를 사용하고,
실수로 다른 PostgreSQL DB를 가리키면 기동 단계에서 실패한다.

다른 터미널에서:

```bash
./loadtest/run-bbs-load.sh
```

공식 runner들은 결과 스냅샷을 저장한 뒤 같은 `LOADTEST_PREFIX` 데이터를 자동으로 정리한다.
이미 남아 있는 과거 loadtest 데이터는 다음처럼 prefix 기준으로 수동 정리할 수 있다.

```bash
./loadtest/cleanup-loadtest-data.sh ltb03250910
./loadtest/cleanup-loadtest-data.sh ltr03250910s01,ltr03250910s02
```

반복 스트레스 + SQL 정합성 검증:

```bash
./loadtest/run-bbs-consistency-stress.sh
```

한계점 탐색용 ramp-up:

```bash
./loadtest/run-bbs-ramp-up.sh
```

sub-1.0 안정 구간 반복 탐색:

```bash
./loadtest/run-bbs-sub-stability.sh
```

시나리오 분해 반복 탐색:

```bash
SCENARIO_PROFILE=browse_search ./loadtest/run-bbs-scenario-sub-stability.sh
```

2-profile 조합 반복 탐색:

```bash
SCENARIO_PROFILE=browse_search+relation_mixed ./loadtest/run-bbs-scenario-sub-stability.sh
```

세분화된 read/relation 조합 반복 탐색:

```bash
SCENARIO_PROFILE=browse_board_feed+search_catalog+relation_mixed ./loadtest/run-bbs-scenario-sub-stability.sh
```

시나리오 matrix 비교:

```bash
./loadtest/run-bbs-scenario-matrix.sh
```

2-profile / 3-profile 조합 matrix 비교:

```bash
BASE_URL=http://localhost:18081 \
RUNS=5 \
STAGE_FACTORS=0.6 \
COMBINATION_SIZES=2,3 \
./loadtest/run-bbs-scenario-combination-matrix.sh
```

read path × relation write pair matrix:

```bash
BASE_URL=http://localhost:18081 \
RUNS=3 \
STAGE_FACTORS=0.6 \
READ_PROFILES=search_catalog,tag_search,sort_catalog \
RELATION_PROFILES=like_mixed,bookmark_mixed,follow_mixed,block_mixed \
./loadtest/run-bbs-read-relation-pair-matrix.sh
```

장시간 soak + SQL 정합성 검증:

```bash
./loadtest/run-bbs-soak.sh
```

## 시나리오

- `browse_board_feed`: 게시판별 목록 조회와 정렬 부하
- `hot_post_details`: 동일한 인기 게시물 상세 조회와 view count 증가 부하
- `search_catalog`: 키워드 검색 부하
- `tag_search_catalog`: 태그 검색 부하
- `sort_catalog`: 정렬 검색 부하
- `write_posts_and_comments`: 게시물 작성 + 핫 게시물 댓글 작성 부하
- `like_idempotent_add_race`: 같은 사용자의 반복 좋아요 요청 경쟁
- `like_idempotent_remove_race`: 같은 사용자의 반복 좋아요 해제 요청 경쟁
- `bookmark_mixed_race`: 같은 사용자의 북마크 등록/해제 혼합 경쟁
- `follow_mixed_race`: 같은 사용자의 팔로우/언팔로우 혼합 경쟁
- `block_mixed_race`: 같은 사용자의 차단/해제 혼합 경쟁
- `notification_read_write_mixed`: 알림 생성과 현재 페이지 읽음 혼합 baseline
- `notification_read_all_stress`: 알림 생성과 `read-all` worst-case 혼합 stress

### 시나리오 profile

- `full`: 기존 broad mixed 전체 시나리오
- `browse_search`: 목록/상세/검색 읽기 경로만 측정
- `browse_board_feed`: 게시판 목록 조회만 측정
- `hot_post_details`: 인기 게시물 상세 조회만 측정
- `search_catalog`: 검색 경로만 측정
- `tag_search`: 태그 검색 경로만 측정
- `sort_catalog`: 정렬 검색 경로만 측정
- `write`: 게시물 작성/댓글 작성만 측정
- `relation_mixed`: like/follow/bookmark/block 혼합 경쟁만 측정
- `like_mixed`: 좋아요 add/remove 경쟁만 측정
- `bookmark_mixed`: 북마크 add/remove 경쟁만 측정
- `follow_mixed`: 팔로우/언팔로우 경쟁만 측정
- `block_mixed`: 차단/해제 경쟁만 측정
- `notification`: 알림 생성/현재 페이지 읽음 baseline 측정
- `notification_stress`: `read-all` worst-case stress 측정
- `browse_search+relation_mixed`: `+` 로 이어 붙인 2-profile 조합도 지원
- `browse_board_feed+search_catalog+relation_mixed`: 세분화된 3-profile 조합도 지원

matrix 예시:

```bash
BASE_URL=http://localhost:18081 \
RUNS=5 \
STAGE_FACTORS=0.5,0.55,0.6 \
SCENARIO_PROFILES=browse_search+relation_mixed,browse_search+notification,write+relation_mixed \
./loadtest/run-bbs-scenario-matrix.sh
```

세분화 예시:

```bash
BASE_URL=http://localhost:18081 \
RUNS=5 \
STAGE_FACTORS=0.5,0.55,0.6 \
SCENARIO_PROFILES=browse_board_feed+relation_mixed,search_catalog+relation_mixed,browse_board_feed+search_catalog+relation_mixed \
./loadtest/run-bbs-scenario-matrix.sh
```

최소 재현 크기만 빠르게 확인하려면:

```bash
BASE_URL=http://localhost:18081 \
RUNS=5 \
STAGE_FACTORS=0.6 \
COMBINATION_SIZES=2 \
STOP_AFTER_FIRST_UNSTABLE_SIZE=1 \
./loadtest/run-bbs-scenario-combination-matrix.sh
```

atomic read path 와 relation write 의 pair 만 빠르게 확인하려면:

```bash
BASE_URL=http://localhost:18081 \
RUNS=3 \
STAGE_FACTORS=0.6 \
READ_PROFILES=search_catalog,tag_search,sort_catalog \
RELATION_PROFILES=like_mixed,bookmark_mixed,follow_mixed,block_mixed \
./loadtest/run-bbs-read-relation-pair-matrix.sh
```

notification baseline/stress 관련 env:

```bash
NOTIFICATION_BASELINE_READ_EVERY=4   # baseline에서 몇 iteration마다 read-page를 수행할지
NOTIFICATION_PAGE_SIZE=20            # baseline read-page batch 크기
NOTIFICATION_MIXED_VUS=20            # baseline notification 시나리오 VU
NOTIFICATION_STRESS_VUS=20           # read-all stress 시나리오 VU
NOTIFICATION_STRESS_DURATION=45s     # read-all stress duration
```

## 결과물

- `loadtest/results/k6-summary-*.json`: 클라이언트 관점 p95/p99, 실패율
- `loadtest/results/server-metrics-*.json`: 서버 관점 duplicate-key 충돌, DB pool timeout, max awaiting connection
- `loadtest/results/consistency-stress-*.md`: 반복 실행별 요약 리포트
- `loadtest/results/consistency-stress-*.tsv`: 반복 실행 집계용 원본 수치
- `loadtest/results/consistency-stress-*-runNN-sql.tsv`: 실행별 SQL 정합성 스냅샷
- `loadtest/results/ramp-up-*.md`: 배수별 한계점 탐색 요약 리포트
- `loadtest/results/ramp-up-*.tsv`: 배수별 원본 수치와 중단 이유
- `loadtest/results/sub-stability-*.md`: `0.75 ~ 1.0` 구간 반복 PASS/LIMIT/FAIL 집계
- `loadtest/results/sub-stability-*.tsv`: sub-1.0 반복 원본 수치
- `loadtest/results/scenario-matrix-*.md`: profile 별 안정 factor 비교 리포트
- `loadtest/results/scenario-matrix-*.tsv`: profile 별 aggregate 경로와 PASS/LIMIT/FAIL 요약
- `loadtest/results/scenario-combination-matrix-*.md`: pair/triple 조합별 stable factor 와 최소 불안정 크기 요약
- `loadtest/results/scenario-combination-matrix-*.tsv`: 조합별 aggregate 경로와 unstable 여부 원본
- `loadtest/results/read-relation-pair-matrix-*.md`: atomic read path × relation write pair 비교 리포트
- `loadtest/results/read-relation-pair-matrix-*.tsv`: read/relation pair 별 aggregate 경로와 unstable 여부 원본
- `loadtest/results/soak-*.md`: 장시간 soak 최종 요약
- `loadtest/results/soak-*-metrics.jsonl`: soak 중 주기적 서버 메트릭 타임라인

## 핵심 지표

- `http_req_duration`, 각 시나리오별 `p(95)`, `p(99)`
- `duplicateKeyConflicts`: 유니크 키 충돌이 실제로 몇 번 났는지
- `dbPoolTimeouts`: 커넥션 풀 획득 timeout 감지 횟수
- `maxThreadsAwaitingConnection`: 부하 중 DB pool 대기 정점
- `postgresSnapshot.waitingSessions`, `lockWaitingSessions`: PostgreSQL 내부 wait event 관측치
- `postgresSnapshot.slowActiveQueries`: 현재 장기 실행 query 와 wait event 스냅샷
- SQL 정합성: `Post.likeCount`, `Post.commentCount`, `User.unreadNotificationCount` 와 실제 row 수 일치 여부
- 관계 중복: `post_likes`, `bookmarks`, `follows`, `blocks` 의 동일 키 중복 row 여부
- 병목 프로파일: `comment.create`, `notification.store`, `post.list`, `post.list.browse.rows`, `post.list.search.rows`, `post.list.tag.rows` 의 벽시계 시간, SQL 실행 시간, SQL 문 수, 트랜잭션 길이
- 분리 검색 메트릭: `tag_search_duration`, `sort_catalog_duration`

## 현재 API hot path

- browse/search load 는 `/api/posts` page endpoint 가 아니라 `/api/posts/slice` cursor endpoint 를 사용한다.
- board browse 는 `latest/likes/comments` 정렬을 지원한다.
- `search_catalog` 는 keyword search 전용이고, `tag_search` 와 `sort_catalog` 는 분리된 scenario 로 측정한다.
- keyword/tag search 는 `latest` 정렬만 지원하며, hot path 에서는 `count(*)` 를 제거한다.

## 데이터 정리 주의사항

- `setup()` 과 `write_posts_and_comments` 는 실제 회원/게시물/댓글을 생성한다.
- 다만 `loadtest` 프로필은 이제 기본 개발 DB가 아니라 전용 `effectivedisco_loadtest` DB를 사용한다.
- 공식 runner는 metrics / SQL snapshot 저장 후 `/internal/load-test/cleanup` 으로 같은 prefix 데이터를 자동 회수한다.
- `k6 run loadtest/k6/bbs-load.js` 를 직접 실행하면 자동 cleanup이 없으므로 `LOADTEST_PREFIX` 를 고정한 뒤 [cleanup-loadtest-data.sh](/home/admin0/effective-disco/loadtest/cleanup-loadtest-data.sh) 로 수동 정리하는 편이 안전하다.
