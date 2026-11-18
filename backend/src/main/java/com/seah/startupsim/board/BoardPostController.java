package com.seah.startupsim.board;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
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
import org.springframework.web.servlet.HandlerMapping;

import static com.seah.startupsim.board.BoardGuestAuth.USER_ID;

@RestController
@RequestMapping("/api/board")
public class BoardPostController {
    private static final String POSTS = "/posts";
    private static final String POST = POSTS + "/{postId}";
    private static final String MINE = "/me/posts";

    private final BoardGuestAuth auth;
    private final BoardPostService posts;

    public BoardPostController(BoardGuestAuth auth, BoardPostService posts) {
        this.auth = auth;
        this.posts = posts;
    }

    /** Model initializer runs before path/query/body binding, preserving 401 → 400. */
    @ModelAttribute
    void authenticate(HttpServletRequest request,
            @RequestHeader(name = "X-Guest-Token", required = false) String token) {
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        boolean read = BoardGuestAuth.isRead(request);
        if (read && ("/api/board" + POSTS).equals(pattern)) return;
        auth.bind(request, token, read && ("/api/board" + POST).equals(pattern));
    }

    @GetMapping(POSTS)
    public PageResponse<PostListItem> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) PostCategory category,
            @RequestParam(required = false) PostSearchQuery.Sort sort,
            @RequestParam(required = false) PostSearchQuery.SearchType searchType,
            @RequestParam(required = false) String q, @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String areaCode) {
        return posts.list(PostSearchQuery.of(page, size, category, sort, searchType, q, serviceCode, areaCode));
    }

    @GetMapping(MINE)
    public ResponseEntity<PageResponse<MyPostListItem>> mine(@RequestAttribute(USER_ID) long userId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) PostCategory category, @RequestParam(required = false) PostSearchQuery.Sort sort,
            @RequestParam(required = false) PostSearchQuery.SearchType searchType,
            @RequestParam(required = false) String q, @RequestParam(required = false) String serviceCode,
            @RequestParam(required = false) String areaCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                posts.mine(PostSearchQuery.of(page, size, category, sort, searchType, q, serviceCode, areaCode), userId));
    }

    @GetMapping(POST)
    public ResponseEntity<PostDetail> detail(@PathVariable long postId,
            @RequestAttribute(name = USER_ID, required = false) Long userId) {
        PostDetail detail = userId == null ? posts.anonymousDetail(postId) : posts.authenticatedDetail(postId, userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail);
    }

    @PostMapping(POSTS)
    public ResponseEntity<PostDetail> create(@RequestAttribute(USER_ID) long userId,
            @Valid @RequestBody CreatePostRequest request) {
        PostDetail post = posts.create(userId, request);
        return ResponseEntity.created(URI.create("/api/board/posts/" + post.postId()))
                .cacheControl(CacheControl.noStore()).body(post);
    }

    @PutMapping(POST)
    public ResponseEntity<PostDetail> update(@PathVariable long postId, @RequestAttribute(USER_ID) long userId,
            @Valid @RequestBody UpdatePostRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(posts.update(postId, userId, request));
    }

    @DeleteMapping(POST)
    public ResponseEntity<Void> delete(@PathVariable long postId, @RequestAttribute(USER_ID) long userId,
            @RequestParam long version) {
        posts.delete(postId, userId, version);
        return ResponseEntity.noContent().build();
    }
}
