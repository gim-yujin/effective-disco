package com.effectivedisco.config;

import com.effectivedisco.domain.Board;
import com.effectivedisco.repository.BoardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 기본 게시판을 자동으로 생성하는 초기화 컴포넌트.
 *
 * CommandLineRunner는 Spring Context가 완전히 초기화된 뒤에 실행된다.
 * 이미 게시판이 하나라도 존재하면 실행을 건너뛰어 멱등성을 보장한다
 * (서버 재시작 시 중복 생성 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardDataInitializer implements CommandLineRunner {

    private final BoardRepository boardRepository;

    @Override
    public void run(String... args) {
        // 게시판이 이미 존재하면 초기화를 건너뜀 (멱등성 보장)
        if (boardRepository.count() > 0) {
            log.info("게시판이 이미 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("기본 게시판을 생성합니다...");

        // 기본 게시판 4개 생성
        boardRepository.save(Board.builder()
                .name("자유게시판").slug("free")
                .description("자유롭게 이야기를 나누는 공간입니다.").build());

        boardRepository.save(Board.builder()
                .name("개발").slug("dev")
                .description("개발·프로그래밍 관련 주제를 다룹니다.").build());

        boardRepository.save(Board.builder()
                .name("질문/답변").slug("qna")
                .description("궁금한 점을 올리면 커뮤니티가 답합니다.").build());

        boardRepository.save(Board.builder()
                .name("공지").slug("notice")
                .description("운영 공지 및 안내사항을 확인하세요.").build());

        log.info("기본 게시판 4개 생성 완료.");
    }
}
