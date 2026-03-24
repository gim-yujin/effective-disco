# Post Detail Comment Scaling

## 문제

- 증상: 댓글이 약 `3,330`개 달린 게시물을 열면 `10초`가 넘어도 상세 화면이 표시되지 않았다.
- 영향: 게시물 본문은 1건인데 댓글이 많아질수록 상세 페이지 응답 시간과 브라우저 렌더링 시간이 급격히 악화됐다.

## 원인

이 문제는 `N+1` 하나로만 설명되지 않았다.

1. 댓글을 한 번에 전부 읽었다.
   - 기존 상세 화면은 게시물 진입 시 최상위 댓글 전체를 모델에 넣었다.
   - 대댓글도 댓글 DTO 재귀 변환 과정에서 같이 전개됐다.

2. 댓글 DTO 생성 과정에서 LAZY fan-out 이 발생했다.
   - `Comment.author` 와 `Comment.replies` 가 모두 LAZY 인데,
   - `CommentResponse(Comment)` 가 댓글마다 `author`, `replies` 를 따라가며 재귀 변환했다.
   - 즉 `최상위 댓글 수 + 대댓글 수`에 비례해 추가 SELECT 가 늘어날 수 있었다.

3. 서버 렌더링 HTML 자체가 과도하게 커졌다.
   - 댓글 본문뿐 아니라 수정 폼, 신고 폼, 답글 폼까지 댓글마다 모두 렌더링했다.
   - 댓글 수가 수천 개면 SQL 이후에도 Thymeleaf 렌더링과 브라우저 DOM 생성 비용이 커진다.

정리하면, 병목은 다음 세 가지의 합이었다.

- `전체 댓글 eager-like loading`
- `author/replies LAZY fan-out`
- `거대한 상세 HTML/DOM`

## 해결 방향

### 1. 최상위 댓글 페이지네이션

- 상세 화면은 `최상위 댓글`만 `Page` 로 잘라서 렌더링한다.
- 기본 크기는 `50`개다.
- 대댓글은 현재 페이지에 포함된 부모 댓글들에 대해서만 함께 렌더링한다.

이 방식의 이유:

- 댓글 3,330개를 한 번에 HTML로 보내지 않는다.
- `count(*) + top-level page + replies batch` 구조로 SQL 수를 상수 수준으로 고정할 수 있다.
- 게시물 상세의 DOM 크기를 댓글 페이지 크기 기준으로 제한할 수 있다.

### 2. 대댓글 배치 조회

- 현재 페이지의 부모 댓글 ID 목록을 모은다.
- `parent_id IN (...)` 한 번으로 대댓글을 전부 읽는다.
- 부모 댓글별 replies map 으로 묶어 DTO 를 조립한다.

이 방식의 이유:

- 부모 댓글마다 `comment.getReplies()` 를 호출하는 N+1 을 제거한다.

### 3. 작성자 projection 사용

- 댓글 목록용 query 는 `author.username`, `author.profileImageUrl` 를 projection 으로 함께 읽는다.
- 상세 댓글 DTO 는 엔티티 그래프를 다시 순회하지 않고 projection row 로 바로 생성한다.

이 방식의 이유:

- 댓글/대댓글마다 `comment.getAuthor()` LAZY SELECT 가 늘어나는 것을 막는다.

### 4. 댓글 앵커 이동 보존

- 댓글이 페이지네이션되면 `#comment-{id}` 앵커만으로는 해당 댓글이 보이는 페이지를 찾을 수 없다.
- 그래서 `commentId -> top-level comment -> commentPage` 를 계산해
  신고/관리 링크도 올바른 댓글 페이지로 이동하게 했다.

## 구현 내용

### 저장소

- `CommentRepository.findTopLevelCommentRowsByPostIdOrderByCreatedAtAsc(...)`
  - 최상위 댓글 projection + `Page`
- `CommentRepository.findReplyRowsByParentIdInOrderByCreatedAtAsc(...)`
  - 현재 페이지 부모 댓글들에 대한 대댓글 batch 조회
- `CommentRepository.findTopLevelCommentIdByCommentId(...)`
  - 임의 댓글이 속한 top-level comment 식별
- `CommentRepository.countTopLevelCommentsBeforeOrAt(...)`
  - 댓글 앵커가 속한 page 계산

### 서비스

- `CommentService.getCommentsPage(postId, page, size)`
  - `top-level page + replies batch` 조립
- `CommentService.getLastTopLevelCommentPage(...)`
  - 새 최상위 댓글 작성 후 마지막 page 로 리다이렉트
- `CommentService.getCommentPageForAnchor(...)`
  - 특정 댓글이 보이는 page 계산

### 웹 상세

- 게시물 상세는 이제 `comments` 와 함께 `commentsPage` 를 모델에 넣는다.
- 템플릿은 현재 페이지 댓글만 렌더링한다.
- 댓글/대댓글 수정·삭제·답글 폼은 `commentPage`, `commentSize` 를 hidden 으로 유지한다.
- 새 최상위 댓글은 마지막 page 로, 답글/수정/삭제는 현재 page 로 복귀한다.

### REST API

- `GET /api/posts/{postId}/comments`
  - 응답 타입을 `List<CommentResponse>` 에서 `Page<CommentResponse>` 로 바꿨다.
  - `page`, `size` 파라미터를 지원한다.

## 인덱스

댓글 페이지네이션과 replies batch 조회를 받쳐주기 위해 다음 인덱스를 추가했다.

- `comments(post_id, parent_id, created_at)`
- `comments(parent_id, created_at)`

## 검증

### 회귀 테스트

- `CommentControllerTest`
  - 댓글 목록 API가 `Page` JSON 구조를 반환하는지 검증
- `BoardWebControllerTest`
  - 상세 페이지 모델에 `commentsPage` 가 포함되는지 검증
  - 댓글/대댓글 CRUD redirect 가 `commentPage/commentSize` 를 유지하는지 검증

### 성능 회귀 방지 테스트

- `CommentPageOptimizationIntegrationTest`
  - `top-level page = 3`, 각 댓글 replies = 2 조건에서
  - Hibernate `prepareStatementCount <= 3` 을 고정

이 기준의 의미:

- `count query`
- `top-level comment page query`
- `reply batch query`

이 3개를 넘기지 않아야 댓글 수가 커져도 SQL fan-out 이 다시 생기지 않는다.

## 결론

- `3330개 댓글 상세 지연`은 N+1 문제의 성격을 포함하지만, 원인은 그것보다 더 넓었다.
- 실질적인 해결책은 `댓글 페이지네이션`이 맞다.
- 다만 페이지네이션만으로는 부족했고,
  `대댓글 batch 조회 + author projection + 상세 DOM 축소`를 함께 적용해야 했다.

## 남은 과제

- 댓글 정렬 방향(`오래된 순`)이 현재 UX에 맞는지 재검토
- 댓글 페이지 번호 UI를 단순 numbered pager 대신 `더 보기` 방식으로 바꿀지 검토
- 실제 대용량 댓글 데이터 기준으로 상세 페이지 p95/p99 재측정
