package cn.mop.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Keep logout and password-change deletion cookies aligned with the transport's session scope. */
final class ProjectSessionCookie {
    private ProjectSessionCookie() {
    }

    static void expire(HttpServletRequest request, HttpServletResponse response) {
        var cookie = new Cookie("MOP_SESSION", "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }
}
