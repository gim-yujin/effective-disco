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
- 상세 원인, 조치, 전후 수치는 [loadtest-optimization.md](loadtest-optimization.md) 에 기록한다.

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
