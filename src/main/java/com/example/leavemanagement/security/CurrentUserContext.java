package com.example.leavemanagement.security;

/** Holds the {@link CurrentUser} resolved by {@link TokenAuthenticationFilter} for the request's thread. */
public final class CurrentUserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    /**
     * The raw bearer token for the current request — kept separate from {@link CurrentUser} (which
     * is returned verbatim by GET /api/userinfo) so the token is never exposed in an API response.
     * Used to propagate the caller's identity to downstream service calls (e.g. the projects
     * service), not for re-validating the caller.
     */
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

    private CurrentUserContext() {}

    public static void set(CurrentUser user, String token) {
        HOLDER.set(user);
        TOKEN_HOLDER.set(token);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** The raw bearer token for the current request, or {@code null} outside a request/when auth is disabled. */
    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
        TOKEN_HOLDER.remove();
    }
}
