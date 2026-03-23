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

## 아직 남은 리스크

### 분산 환경

- 현재 검증은 단일 DB + 단일 애플리케이션 프로세스 기준
- 멀티 인스턴스 환경의 SSE fan-out, 로컬 메모리 상태 일관성은 별도 검토 필요

### 장시간/대규모 스트레스

- 현재는 짧은 경쟁 테스트로 정합성 불변식 확인
- 장시간 soak test 와 더 높은 worker 수에서 deadlock/timeout 패턴 추가 검증 가능

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
