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

## 시나리오

- `browse_board_feed`: 게시판별 목록 조회와 정렬 부하
- `hot_post_details`: 동일한 인기 게시물 상세 조회와 view count 증가 부하
- `search_catalog`: 키워드/태그/정렬 검색 부하
- `write_posts_and_comments`: 게시물 작성 + 핫 게시물 댓글 작성 부하
- `like_idempotent_add_race`: 같은 사용자의 반복 좋아요 요청 경쟁
- `like_idempotent_remove_race`: 같은 사용자의 반복 좋아요 해제 요청 경쟁

## 결과물

- `loadtest/results/k6-summary-*.json`: 클라이언트 관점 p95/p99, 실패율
- `loadtest/results/server-metrics-*.json`: 서버 관점 duplicate-key 충돌, DB pool timeout, max awaiting connection

## 핵심 지표

- `http_req_duration`, 각 시나리오별 `p(95)`, `p(99)`
- `duplicateKeyConflicts`: 유니크 키 충돌이 실제로 몇 번 났는지
- `dbPoolTimeouts`: 커넥션 풀 획득 timeout 감지 횟수
- `maxThreadsAwaitingConnection`: 부하 중 DB pool 대기 정점
