package com.nexacheckin;

import org.json.JSONObject;

final class Account {
    final int slot;
    final String revision, label, credential, userId, userJson, refreshToken;
    final long expiresAt;
    final Site site;
    final String routerUnit, userAgent;
    Account(int slot, String revision, String label, String credential, String userId, String userJson) {
        this(slot, revision, label, credential, userId, userJson, "", 0);
    }
    Account(int slot, String revision, String label, String credential, String userId, String userJson,
            String refreshToken, long expiresAt) {
        this(slot, revision, label, credential, userId, userJson, refreshToken, expiresAt, Site.NEXA, "", "");
    }
    Account(int slot, String revision, String label, String credential, String userId, String userJson,
            String refreshToken, long expiresAt, Site site, String routerUnit, String userAgent) {
        if (site == null || routerUnit == null || userAgent == null || userAgent.length() > 1024
                || userAgent.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid provider metadata");
        if (!routerUnit.isEmpty()) {
            try { if (Policy.cost(routerUnit).signum() <= 0 || routerUnit.length() > 32) throw new Exception(); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid quota unit"); }
        }
        if (site.router() && (!refreshToken.isEmpty() || expiresAt != 0 || !Policy.cookie(credential)))
            throw new IllegalArgumentException("Invalid router session");
        if (slot < 1 || slot > 9 || revision == null || revision.isEmpty()
                || label == null || label.trim().isEmpty() || label.length() > 40)
            throw new IllegalArgumentException("Invalid account");
        try {
            if (!credential.equals(Policy.credential(credential)) || userJson.length() > 32000
                    || !userId.equals(Policy.userId(new JSONObject(userJson))))
                throw new IllegalArgumentException("Invalid identity");
        } catch (Exception e) { throw new IllegalArgumentException("Invalid identity"); }
        if (refreshToken == null || expiresAt < 0 || expiresAt > 9007199254740991L)
            throw new IllegalArgumentException("Invalid refresh metadata");
        if (!refreshToken.isEmpty()) TokenPair.token(refreshToken);
        this.slot = slot; this.revision = revision; this.label = label;
        this.credential = credential; this.userId = userId; this.userJson = userJson;
        this.refreshToken = refreshToken; this.expiresAt = expiresAt;
        this.site = site; this.routerUnit = routerUnit; this.userAgent = userAgent;
    }
    String identityKey() { return site.id + ":" + userId; }
    Account renamed(String name) {
        return new Account(slot, java.util.UUID.randomUUID().toString(), name, credential, userId, userJson, refreshToken, expiresAt, site, routerUnit, userAgent);
    }
    JSONObject json() throws Exception {
        return new JSONObject().put("slot", slot).put("revision", revision).put("label", label)
            .put("credential", credential).put("userId", userId).put("userJson", userJson)
            .put("refreshToken", refreshToken).put("expiresAt", expiresAt)
            .put("site", site.id).put("routerUnit", routerUnit).put("userAgent", userAgent);
    }
    static Account from(JSONObject j) throws Exception {
        int slot = j.getInt("slot");
        if (slot < 1 || slot > 9) throw new IllegalArgumentException("Invalid slot");
        return new Account(slot, j.getString("revision"), j.getString("label"),
            j.getString("credential"), j.getString("userId"), j.getString("userJson"),
            j.optString("refreshToken", ""), j.optLong("expiresAt", 0), Site.from(j.optString("site", "nexa")),
            j.optString("routerUnit", ""), j.optString("userAgent", ""));
    }
}
