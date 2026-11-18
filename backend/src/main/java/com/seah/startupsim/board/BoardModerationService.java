package com.seah.startupsim.board;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.seah.startupsim.board.BoardModerationRepository.*;

@Service
public class BoardModerationService {
    private final BoardModerationRepository repository;

    public BoardModerationService(BoardModerationRepository repository) {
        this.repository = repository;
    }

    private BoardException conflict() {
        return new BoardException(HttpStatus.CONFLICT, "STATE_CONFLICT", "현재 상태에서는 이 조치를 할 수 없습니다.");
    }

    @Transactional
    public void handle(long id, ReportStatus action, String principal) {
        BoardWrites.safe(() -> {
            var initial = repository.report(id);
            var target = repository.lockTarget(initial.type(), initial.targetId(), false);
            var report = repository.report(id); // Re-read after obtaining the common target lock.
            if (report.status() == action) return null;
            if (report.status() != ReportStatus.PENDING) throw conflict();
            if (action == ReportStatus.RESOLVED && target.status() == State.ACTIVE) {
                repository.change(report.type(), report.targetId(), target.postId(), State.HIDDEN);
            }
            repository.handle(report, action, principal);
            return null;
        });
    }

    @Transactional
    public void change(TargetType type, long id, State next) {
        BoardWrites.safe(() -> {
            var target = repository.lockTarget(type, id, false);
            State current = target.status();
            if (current == next) return null;
            if (current == State.DELETED || (next == State.ACTIVE && current != State.HIDDEN)
                    || (next == State.HIDDEN && current != State.ACTIVE)) throw conflict();
            repository.change(type, id, target.postId(), next);
            return null;
        });
    }

    @Transactional
    public void pin(long id, boolean pinned) {
        BoardWrites.safe(() -> {
            var target = repository.lockTarget(TargetType.POST, id, false);
            if (target.status() != State.ACTIVE) throw conflict();
            if (target.pinned() != pinned) repository.pin(id, pinned);
            return null;
        });
    }
}
