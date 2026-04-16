package com.effectivedisco.service;

import org.springframework.security.access.AccessDeniedException;

/**
 * 리소스 소유권 검증 유틸리티.
 *
 * 게시물·댓글 등의 수정/삭제 시 요청자가 소유자인지 확인한다.
 */
public final class OwnershipChecker {

    private OwnershipChecker() {}

    /**
     * 리소스 소유자와 요청자가 동일한지 확인한다.
     *
     * @param ownerUsername   리소스 소유자 username
     * @param requestUsername 요청자 username
     * @throws AccessDeniedException 소유자가 아닌 경우
     */
    public static void check(String ownerUsername, String requestUsername) {
        if (!ownerUsername.equals(requestUsername)) {
            throw new AccessDeniedException("수정/삭제 권한이 없습니다");
        }
    }
}
