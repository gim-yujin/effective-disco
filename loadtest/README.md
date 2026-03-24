# BBS Load Test

Redis/Kafka 없이 애플리케이션 자체 진단 경로와 `k6`만으로 게시판 부하를 측정한다.

## 준비

```bash
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
```

다른 터미널에서:

```bash
./loadtest/run-bbs-load.sh
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

시나리오 matrix 비교:

```bash
./loadtest/run-bbs-scenario-matrix.sh
```

장시간 soak + SQL 정합성 검증:

```bash
./loadtest/run-bbs-soak.sh
```

## 시나리오

- `browse_board_feed`: 게시판별 목록 조회와 정렬 부하
- `hot_post_details`: 동일한 인기 게시물 상세 조회와 view count 증가 부하
- `search_catalog`: 키워드/태그/정렬 검색 부하
- `write_posts_and_comments`: 게시물 작성 + 핫 게시물 댓글 작성 부하
- `like_idempotent_add_race`: 같은 사용자의 반복 좋아요 요청 경쟁
- `like_idempotent_remove_race`: 같은 사용자의 반복 좋아요 해제 요청 경쟁
- `bookmark_mixed_race`: 같은 사용자의 북마크 등록/해제 혼합 경쟁
- `follow_mixed_race`: 같은 사용자의 팔로우/언팔로우 혼합 경쟁
- `block_mixed_race`: 같은 사용자의 차단/해제 혼합 경쟁
- `notification_read_write_mixed`: 알림 생성과 전체 읽음 처리 혼합 경쟁

### 시나리오 profile

- `full`: 기존 broad mixed 전체 시나리오
- `browse_search`: 목록/상세/검색 읽기 경로만 측정
- `write`: 게시물 작성/댓글 작성만 측정
- `relation_mixed`: like/follow/bookmark/block 혼합 경쟁만 측정
- `notification`: 알림 생성/읽음 처리만 측정

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
- 병목 프로파일: `comment.create`, `notification.store`, `post.list` 의 벽시계 시간, SQL 실행 시간, SQL 문 수, 트랜잭션 길이
