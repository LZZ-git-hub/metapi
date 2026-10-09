package com.nexacheckin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/** Non-secret login transaction; only MainActivity commits to the encrypted vault. */
final class LoginRequest {
    final int slot;
    final Site site;
    final String label, nonce, revision, userId;
    LoginRequest(int slot, String label, String nonce, String revision, String userId) {
        this(slot, label, nonce, revision, userId, Site.NEXA);
    }
    LoginRequest(int slot, String label, String nonce, String revision, String userId, Site site) {
        if (site == null) throw new IllegalArgumentException("无效站点");
        this.site = site;
        if (slot < 1 || slot > 9 || label == null || label.trim().isEmpty() || label.length() > 40
                || nonce == null || nonce.isEmpty() || revision == null || userId == null
                || revision.isEmpty() != userId.isEmpty()) throw new IllegalArgumentException("无效的登录请求");
        this.slot = slot; this.label = label; this.nonce = nonce; this.revision = revision; this.userId = userId;
    }
    static LoginRequest create(int slot, String label, Account original) {
        if (original != null && original.slot != slot) throw new IllegalArgumentException("账号槽位不匹配");
        return create(slot, label, original, original == null ? Site.NEXA : original.site);
    }
    static LoginRequest create(int slot, String label, Account original, Site site) {
        if (original != null && (original.slot != slot || original.site != site)) throw new IllegalArgumentException("账号站点不匹配");
        return new LoginRequest(slot, label, UUID.randomUUID().toString(),
            original == null ? "" : original.revision, original == null ? "" : original.userId, site);
    }
    JSONObject json() throws Exception {
        return new JSONObject().put("slot", slot).put("label", label).put("nonce", nonce)
            .put("revision", revision).put("userId", userId).put("site", site.id);
    }
    static LoginRequest from(String raw) throws Exception {
        JSONObject j = new JSONObject(raw);
        return new LoginRequest(j.getInt("slot"), j.getString("label"), j.getString("nonce"),
            j.getString("revision"), j.getString("userId"), Site.from(j.optString("site", "nexa")));
    }
    List<Account> accept(List<Account> current, String returnedNonce, Account candidate) throws Exception {
        if (site != candidate.site || !nonce.equals(returnedNonce) || slot != candidate.slot || !label.equals(candidate.label))
            throw new Exception("登录结果已过期，请重新登录");
        Account previous = null;
        for (Account a : current) {
            if (a.slot == slot) previous = a;
            else if (a.identityKey().equals(candidate.identityKey())) throw new Exception("该原站账号已添加");
        }
        if (revision.isEmpty() ? previous != null : previous == null || !revision.equals(previous.revision))
            throw new Exception("账号已发生变化，请重新登录");
        if (previous != null && previous.site != site) throw new Exception("账号站点已变化，请重新登录");
        if (!userId.isEmpty() && !userId.equals(candidate.userId))
            throw new Exception("登录的是其他账号，请单独添加；原账号未更改");
        List<Account> next = new ArrayList<>(current);
        next.removeIf(a -> a.slot == slot); next.add(candidate);
        next.sort(Comparator.comparingInt(a -> a.slot));
        return next;
    }
}
