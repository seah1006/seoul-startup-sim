package com.seah.startupsim.board;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.seah.startupsim.board.BoardGuestAuth.USER_ID;

@RestController
@RequestMapping("/api/board/posts/{postId}/comments")
public class BoardCommentController {
    private final BoardGuestAuth auth;
    private final BoardCommentService comments;

    public BoardCommentController(BoardGuestAuth auth, BoardCommentService comments) {
        this.auth = auth;
        this.comments = comments;
    }

    @ModelAttribute
    void authenticate(HttpServletRequest request,
            @RequestHeader(name = "X-Guest-Token", required = false) String token) {
        auth.bind(request, token, BoardGuestAuth.isRead(request));
    }

    @GetMapping
    public ResponseEntity<PageResponse<CommentItem>> roots(@PathVariable long postId,
            @RequestAttribute(name = USER_ID, required = false) Long userId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comments.list(postId, null, userId, page, size));
    }

    @GetMapping("/{commentId}/replies")
    public ResponseEntity<PageResponse<CommentItem>> replies(@PathVariable long postId, @PathVariable long commentId,
            @RequestAttribute(name = USER_ID, required = false) Long userId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comments.list(postId, commentId, userId, page, size));
    }

    @PostMapping
    public ResponseEntity<CommentItem> create(@PathVariable long postId, @RequestAttribute(USER_ID) long userId,
            @Valid @RequestBody CreateCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(comments.create(postId, userId, request));
    }

    @PutMapping("/{commentId}")
    public ResponseEntity<CommentItem> update(@PathVariable long postId, @PathVariable long commentId,
            @RequestAttribute(USER_ID) long userId, @Valid @RequestBody UpdateCommentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(comments.update(postId, commentId, userId, request));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable long postId, @PathVariable long commentId,
            @RequestAttribute(USER_ID) long userId, @RequestParam long version) {
        comments.delete(postId, commentId, userId, version);
        return ResponseEntity.noContent().build();
    }
}
