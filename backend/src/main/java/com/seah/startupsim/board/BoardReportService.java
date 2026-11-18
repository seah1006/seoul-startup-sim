package com.seah.startupsim.board;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardReportService {
    private final BoardModerationRepository repository;
    private final BoardRateLimiter limiter;

    public BoardReportService(BoardModerationRepository repository, BoardRateLimiter limiter) {
        this.repository = repository;
        this.limiter = limiter;
    }

    @Transactional
    public ReportCreated create(long reporter, CreateReportRequest request) {
        String detail = request.detail() == null ? null : request.detail().strip();
        if ("ETC".equals(request.reason()) && (detail == null || detail.isEmpty())) {
            throw BoardException.invalid("기타 신고 사유를 입력해 주세요.");
        }
        var type = BoardModerationRepository.TargetType.valueOf(request.targetType());
        return BoardWrites.safe(() -> {
            var target = repository.lockTarget(type, request.targetId(), true);
            if (target.authorId() == reporter) {
                throw new BoardException(HttpStatus.FORBIDDEN, "FORBIDDEN", "자신의 글이나 댓글은 신고할 수 없습니다.");
            }
            if (repository.hasReport(type, request.targetId(), reporter)) throw BoardException.alreadyReported();
            limiter.acquire(BoardRateLimiter.Kind.REPORT, reporter);
            return new ReportCreated(repository.report(type, request.targetId(), reporter, request.reason(), detail), "PENDING");
        });
    }
}
