package com.seah.startupsim.board;

import com.seah.startupsim.auth.AuthException;
import com.seah.startupsim.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** API 보안 체인이 확인한 사용자로 게시판 요청을 연결한다. 쓰기와 내 글 조회는 회원만 허용한다. */
@Component
public class BoardGuestAuth {
    static final String USER_ID = "boardUserId";
    private static final String MEMBER = "MEMBER";

    private static AuthenticatedUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    /** 토큰 없음은 401, 게스트 토큰은 403 MEMBER_REQUIRED. */
    public long member() {
        AuthenticatedUser user = current();
        if (user == null) throw AuthException.unauthorized();
        if (!MEMBER.equals(user.role())) throw new AuthException(403, "MEMBER_REQUIRED", "회원 인증이 필요합니다.");
        return user.userId();
    }

    /** 공개 읽기에서 토큰이 있을 때만 사용자(게스트 포함)를 돌려준다. */
    public Long optional() {
        AuthenticatedUser user = current();
        return user == null ? null : user.userId();
    }

    static boolean isRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    void bind(HttpServletRequest request, boolean optional) {
        Long userId = optional ? optional() : Long.valueOf(member());
        if (userId != null) request.setAttribute(USER_ID, userId);
    }
}
