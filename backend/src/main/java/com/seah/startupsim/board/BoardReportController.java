package com.seah.startupsim.board;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/board/reports")
public class BoardReportController {
    private final BoardGuestAuth auth;
    private final BoardReportService reports;

    public BoardReportController(BoardGuestAuth auth, BoardReportService reports) {
        this.auth = auth;
        this.reports = reports;
    }

    @ModelAttribute
    void authenticate(HttpServletRequest request,
            @RequestHeader(name = "X-Guest-Token", required = false) String token) {
        auth.bind(request, token, false);
    }

    @PostMapping
    public ResponseEntity<ReportCreated> create(@RequestAttribute(BoardGuestAuth.USER_ID) long userId,
            @Valid @RequestBody CreateReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(reports.create(userId, request));
    }
}
