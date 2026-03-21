package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 게시판(板) 엔티티.
 * Reddit의 서브레딧, 5ch의 板과 동일한 개념으로,
 * 게시물을 주제별로 분류하는 최상위 범주다.
 */
@Entity
@Table(name = "boards")
@Getter
@NoArgsConstructor
public class Board {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 게시판 표시 이름 (예: "자유게시판") */
    @Column(nullable = false)
    private String name;

    /**
     * URL에 사용되는 슬러그 (예: "free" → /boards/free).
     * 영문 소문자·숫자·하이픈만 허용하며 유일해야 한다.
     */
    @Column(nullable = false, unique = true)
    private String slug;

    /** 게시판 한 줄 설명 */
    @Column
    private String description;

    /** 게시판 생성 시각 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 이 게시판에 속한 게시물 목록.
     * LAZY 로딩 — 게시판 목록 페이지에서 직접 접근하지 않는다.
     */
    @OneToMany(mappedBy = "board")
    private List<Post> posts = new ArrayList<>();

    @Builder
    public Board(String name, String slug, String description) {
        this.name        = name;
        this.slug        = slug;
        this.description = description;
        this.createdAt   = LocalDateTime.now();
    }
}
