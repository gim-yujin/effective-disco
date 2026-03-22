package com.effectivedisco.domain;

/** 알림 종류. DB에 문자열로 저장된다. */
public enum NotificationType {
    /** 내 게시물에 댓글이 달렸을 때 */
    COMMENT,
    /** 내 댓글에 대댓글이 달렸을 때 */
    REPLY,
    /** 내 게시물에 좋아요가 눌렸을 때 */
    LIKE,
    /** 새 쪽지(DM)가 도착했을 때 */
    MESSAGE
}
