package com.effectivedisco.service;

/**
 * 페이지네이션 관련 유틸리티.
 */
public final class PaginationUtils {

    private PaginationUtils() {}

    /**
     * 페이지 크기를 [1, maxSize] 범위로 고정한다.
     *
     * @param size    요청된 페이지 크기
     * @param maxSize 허용되는 최대 페이지 크기
     * @return 범위 내로 고정된 페이지 크기
     */
    public static int clampPageSize(int size, int maxSize) {
        return Math.max(1, Math.min(size, maxSize));
    }
}
