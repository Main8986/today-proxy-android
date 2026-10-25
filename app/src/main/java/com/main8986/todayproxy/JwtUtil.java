package com.main8986.todayproxy;

import java.util.Base64;

import org.json.JSONObject;

/** JWT 只读解析（不验签）：拿 sub 和 exp。 */
public final class JwtUtil {
    private JwtUtil() {}

    private static JSONObject payload(String jwt) {
        try {
            String[] p = jwt.split("\\.");
            if (p.length < 2) return null;
            byte[] raw = Base64.getUrlDecoder().decode(p[1]);
            return new JSONObject(new String(raw, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    public static Long exp(String jwt) {
        JSONObject o = payload(jwt);
        if (o == null || !o.has("exp")) return null;
        return o.optLong("exp", 0);
    }

    public static String sub(String jwt) {
        JSONObject o = payload(jwt);
        return o == null ? null : o.optString("sub", null);
    }
}
