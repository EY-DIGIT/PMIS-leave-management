package com.example.leavemanagement.security;

/** Holds the {@link CurrentUser} resolved by {@link TokenAuthenticationFilter} for the request's thread. */
public final class CurrentUserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserContext() {}

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
