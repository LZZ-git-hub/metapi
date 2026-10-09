package com.nexacheckin;

import java.net.URI;

/** Fixed provider allow-list. Credentials and browser profiles never move between providers. */
enum Site {
    NEXA("nexa", "Nexa", "https://asia.nexavlinks.com"),
    AGENT("agentrouter", "AgentRouter", "https://agentrouter.org"),
    ANY("anyrouter", "AnyRouter", "https://anyrouter.top"),
    CONGEE("congee", "Congee（粥）", "https://invite.congee.pro");
    final String id, label, origin;
    Site(String id, String label, String origin) { this.id = id; this.label = label; this.origin = origin; }
    static Site from(String id) {
        for (Site site : values()) if (site.id.equals(id)) return site;
        throw new IllegalArgumentException("不支持的站点");
    }
    boolean router() { return this == AGENT || this == ANY; }
    boolean balanceOnly() { return this == CONGEE; }
    boolean tokenSession() { return this == NEXA || this == CONGEE; }
    boolean supportsCheckin() { return this == NEXA || router(); }
    String accountPath() {
        if (balanceOnly()) return "/dashboard";
        return router() ? "/console" : "/check-in";
    }
    boolean tokenApiPath(String path) {
        if (!tokenSession()) return false;
        if ("/auth/me".equals(path)) return true;
        return this == NEXA && ("/usage/dashboard/stats".equals(path) || "/check-in".equals(path) || "/subscriptions".equals(path));
    }
    boolean owns(String url) { return sameOrigin(origin, url); }
    boolean loginUrl(String url) {
        return owns(url) || (router() || balanceOnly()) && (sameOrigin("https://connect.linux.do", url) || sameOrigin("https://linux.do", url));
    }
    static boolean sameOrigin(String expected, String url) {
        try {
            URI target = new URI(url), origin = new URI(expected);
            return "https".equalsIgnoreCase(target.getScheme()) && origin.getHost().equalsIgnoreCase(target.getHost())
                && (target.getPort() == -1 || target.getPort() == 443) && target.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }
}
