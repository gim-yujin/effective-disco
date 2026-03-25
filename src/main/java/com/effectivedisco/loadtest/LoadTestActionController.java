package com.effectivedisco.loadtest;

import com.effectivedisco.service.BlockService;
import com.effectivedisco.service.BookmarkService;
import com.effectivedisco.service.FollowService;
import com.effectivedisco.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문제 해결:
 * follow/bookmark/block/notification 읽음 처리는 공개 REST API가 아니라
 * 세션/CSRF 기반 웹 폼에 묶여 있다. loadtest 프로필 전용 내부 래퍼를 두어
 * 운영 표면을 넓히지 않으면서도 k6가 서비스의 동시성 로직을 직접 두드릴 수 있게 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/load-test/actions")
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestActionController {

    private final FollowService followService;
    private final BookmarkService bookmarkService;
    private final BlockService blockService;
    private final NotificationService notificationService;

    @PostMapping("/follow")
    public ToggleStateResponse follow(@RequestBody UserPairActionRequest request) {
        followService.follow(request.actorUsername(), request.targetUsername());
        return new ToggleStateResponse(
                followService.isFollowing(request.actorUsername(), request.targetUsername()));
    }

    @PostMapping("/unfollow")
    public ToggleStateResponse unfollow(@RequestBody UserPairActionRequest request) {
        followService.unfollow(request.actorUsername(), request.targetUsername());
        return new ToggleStateResponse(
                followService.isFollowing(request.actorUsername(), request.targetUsername()));
    }

    @PostMapping("/bookmark")
    public ToggleStateResponse bookmark(@RequestBody BookmarkActionRequest request) {
        bookmarkService.bookmark(request.username(), request.postId());
        return new ToggleStateResponse(
                bookmarkService.isBookmarked(request.username(), request.postId()));
    }

    @PostMapping("/unbookmark")
    public ToggleStateResponse unbookmark(@RequestBody BookmarkActionRequest request) {
        bookmarkService.unbookmark(request.username(), request.postId());
        return new ToggleStateResponse(
                bookmarkService.isBookmarked(request.username(), request.postId()));
    }

    @PostMapping("/block")
    public ToggleStateResponse block(@RequestBody UserPairActionRequest request) {
        blockService.block(request.actorUsername(), request.targetUsername());
        return new ToggleStateResponse(
                blockService.isBlocking(request.actorUsername(), request.targetUsername()));
    }

    @PostMapping("/unblock")
    public ToggleStateResponse unblock(@RequestBody UserPairActionRequest request) {
        blockService.unblock(request.actorUsername(), request.targetUsername());
        return new ToggleStateResponse(
                blockService.isBlocking(request.actorUsername(), request.targetUsername()));
    }

    /**
     * 문제 해결:
     * 알림 생성과 "전체 읽음"을 같은 런 안에서 섞어야 unread counter drift를 실제 HTTP 경로로 재현할 수 있다.
     * 다만 mixed scenario는 목록 렌더링이 아니라 상태 전환 비용만 보면 되므로,
     * full list/full count 대신 상태 전환량만 요약하는 경로를 호출해 pool 포화 원인을 더 정확히 분리한다.
     */
    @PostMapping("/notifications/read-all")
    public NotificationReadResponse markAllNotificationsRead(@RequestBody UsernameActionRequest request) {
        NotificationService.NotificationReadSummary summary =
                notificationService.markAllAsReadForLoadTest(request.username());
        return new NotificationReadResponse(summary.listedNotificationCount(), summary.unreadCount());
    }

    /**
     * 문제 해결:
     * baseline load test 는 현실적인 "현재 페이지 읽음" 경로를 따로 측정해야 한다.
     * full/baseline 시나리오는 page batch 만 읽음 처리하고,
     * `/notifications/read-all` 은 worst-case stress 전용 경로로 남긴다.
     */
    @PostMapping("/notifications/read-page")
    public NotificationReadResponse markNotificationPageRead(@RequestBody NotificationPageActionRequest request) {
        NotificationService.NotificationReadSummary summary =
                notificationService.markPageAsReadForLoadTest(request.username(), request.page(), request.size());
        return new NotificationReadResponse(summary.listedNotificationCount(), summary.unreadCount());
    }

    public record UserPairActionRequest(String actorUsername, String targetUsername) {}

    public record BookmarkActionRequest(String username, Long postId) {}

    public record UsernameActionRequest(String username) {}

    public record NotificationPageActionRequest(String username, int page, int size) {}

    public record ToggleStateResponse(boolean active) {}

    public record NotificationReadResponse(int listedNotificationCount, long unreadCount) {}
}
