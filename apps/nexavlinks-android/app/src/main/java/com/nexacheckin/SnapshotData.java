package com.nexacheckin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Snapshot envelope bound to origin, slot, user and credential revision; contains no credentials. */
final class SnapshotData {
    static JSONArray encode(List<Account> accounts, Map<Integer, Progress> values) throws Exception {
        JSONArray result = new JSONArray();
        for (Account a : accounts) {
            Progress p = values.get(a.slot);
            if (p != null) result.put(new JSONObject().put("origin", a.site.origin).put("slot", a.slot)
                .put("userId", a.userId).put("revision", a.revision).put("progress", p.json()));
        }
        return result;
    }
    static Map<Integer, Progress> decode(List<Account> accounts, JSONArray values) throws Exception {
        Map<Integer, Progress> result = new HashMap<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject j = values.getJSONObject(i);
            for (Account a : accounts) {
                if (a.site.origin.equals(j.optString("origin")) && a.slot == j.optInt("slot")
                        && a.userId.equals(j.optString("userId")) && a.revision.equals(j.optString("revision"))) {
                    result.put(a.slot, Progress.from(j.getJSONObject("progress")));
                    break;
                }
            }
        }
        return result;
    }
}
