package com.main8986.todayproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 单个 Today 账号的客户端：换 JWT、发消息、轮询回复、切模型。
 * 对应 today-proxy/core.py 的 TodayClient。
 */
public class Account {

    public final String token;        // Today session token（32 位）
    public volatile String label;
    public volatile double weight = 1.0;

    // ---- 运行期状态（号池用）----
    public volatile String userId;
    public volatile String plan;
    public volatile String selectedModel;
    public volatile Double usagePercent;   // null = 未知
    public volatile Integer resetCount;    // 可用重置次数，null = 未查
    public volatile int inflight;
    public volatile long cooldownUntil;    // epoch millis，0 = 正常
    public volatile String lastError = "";
    public volatile int okCount, errCount;
    public volatile int fails;

    private volatile String jwt;
    private volatile long jwtExp;         // epoch seconds
    private final Object lock = new Object();

    public Account(String token, String label, double weight) {
        this.token = token;
        this.label = label;
        this.weight = weight;
    }

    public boolean healthy(long now) {
        return cooldownUntil <= now
                && (usagePercent == null || usagePercent < Pool.MAX_USAGE);
    }

    public long cooldownLeft(long now) {
        long d = cooldownUntil - now;
        return d > 0 ? d / 1000 : 0;
    }

    // ------------------------------------------------------------------ JWT
    public String jwt() throws ApiException {
        synchronized (lock) {
            long now = System.currentTimeMillis() / 1000;
            if (jwt != null && now < jwtExp - 20) return jwt;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(TodayApi.AUTH_BASE + "/api/auth/token").openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Origin", "https://todayai.cn");
                c.setRequestProperty("X-Client-Platform", "android");
                c.setRequestProperty("X-App-Version", TodayApi.APP_VERSION);
                c.setRequestProperty("User-Agent", TodayApi.userAgent(""));
                int code = c.getResponseCode();
                String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code != 200) throw new ApiException(code, "session token exchange failed: " + trim(body));
                JSONObject o = new JSONObject(body);
                jwt = o.getString("token");
                Long exp = JwtUtil.exp(jwt);
                jwtExp = exp != null ? exp : (now + 115);
                String sub = JwtUtil.sub(jwt);
                if (sub != null) userId = sub;
                return jwt;
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(0, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    public void forceRefreshJwt() {
        synchronized (lock) { jwt = null; jwtExp = 0; }
    }

    // ------------------------------------------------------------------ HTTP
    private String call(String method, String path, String jsonBody, int timeoutSec) throws ApiException {
        HttpURLConnection c = null;
        try {
            String url = TodayApi.API_BASE + path;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutSec * 1000);
            c.setRequestProperty("Authorization", "Bearer " + jwt());
            c.setRequestProperty("X-Client-Platform", "android");
            c.setRequestProperty("X-Timezone", "Asia/Shanghai");
            c.setRequestProperty("Accept-Language", "zh-CN");
            c.setRequestProperty("X-App-Version", TodayApi.APP_VERSION);
            c.setRequestProperty("X-Client-Capabilities", TodayApi.capabilities());
            c.setRequestProperty("Origin", "https://todayai.cn");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", TodayApi.userAgent(""));
            if (userId != null) c.setRequestProperty("X-User-Id", userId);
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) { os.write(b); }
            }
            int code = c.getResponseCode();
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401) { forceRefreshJwt(); throw new ApiException(401, "unauthorized"); }
            return body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 业务接口；收到 401 会自动换一次 JWT 再试。 */
    public String api(String method, String path, String body, int timeoutSec) throws ApiException {
        try {
            return call(method, path, body, timeoutSec);
        } catch (ApiException e) {
            if (e.code == 401) {
                forceRefreshJwt();
                return call(method, path, body, timeoutSec);
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ 业务
    public void refreshStatus() throws ApiException {
        String body = api("GET", "/v1/pricing/status", null, 30);
        try {
            JSONObject root = new JSONObject(body);
            JSONObject d = root.optJSONObject("data");
            JSONObject st = d == null ? null : d.optJSONObject("status");
            if (st == null) return;
            plan = st.optString("plan", null);
            JSONObject usage = st.optJSONObject("usage");
            if (usage != null) {
                if (usage.has("progress") && !usage.isNull("progress")) {
                    usagePercent = usage.optDouble("progress");
                } else {
                    String txt = usage.optString("progressText", "");
                    int i = txt.indexOf("剩余");
                    if (i >= 0) {
                        String num = txt.substring(i + 2).replaceAll("[^0-9.]", "");
                        if (!num.isEmpty()) {
                            try { usagePercent = 100.0 - Double.parseDouble(num); } catch (Exception ignore) {}
                        }
                    }
                }
            }
            JSONObject mo = st.optJSONObject("modelOptions");
            if (mo != null) selectedModel = mo.optString("selectedOptionId", null);
            // 顺带查一次重置次数（只读）
            try {
                resetQuota();
            } catch (ApiException ignore) {
            }
        } catch (Exception e) {
            throw new ApiException(0, "parse pricing: " + e.getMessage());
        }
    }

    /**
     * 查询可用重置次数。GET /v1/pricing/resets 只读，不消耗次数。
     * 实测抓包：{"data":{"items":[{"resetId","expiresAt"}],"totalCount":2}}
     */
    public int resetQuota() throws ApiException {
        String body = api("GET", "/v1/pricing/resets", null, 30);
        try {
            JSONObject d = new JSONObject(body).optJSONObject("data");
            if (d == null) throw new ApiException(0, "no data");
            int total = d.optInt("totalCount", 0);
            resetCount = total;
            return total;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "parse resets: " + e.getMessage());
        }
    }

    /**
     * **会真的消耗一次重置配额**。调用前必须由用户明确确认。
     *
     * POST /v1/pricing/resets  {"resetId":"<uuid>"}
     * → {"data":{"receipt":{"consumedCount":1,"availableCountAfter":2}}}
     *
     * @return 重置后剩余次数
     */
    public int consumeReset(String resetId) throws ApiException {
        // 先取可用的 resetId 列表
        String listBody = api("GET", "/v1/pricing/resets", null, 30);
        String rid = resetId;
        try {
            JSONObject d = new JSONObject(listBody).optJSONObject("data");
            JSONArray items = d == null ? null : d.optJSONArray("items");
            if (items == null || items.length() == 0) {
                throw new ApiException(409, "没有可用的重置次数");
            }
            if (rid == null || rid.isEmpty()) {
                rid = items.optJSONObject(0).optString("resetId");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "parse resets: " + e.getMessage());
        }

        JSONObject req = new JSONObject();
        try {
            req.put("resetId", rid);
        } catch (Exception ignore) {}
        String body = api("POST", "/v1/pricing/resets", req.toString(), 60);
        try {
            JSONObject d = new JSONObject(body).optJSONObject("data");
            JSONObject rc = d == null ? null : d.optJSONObject("receipt");
            int after = rc == null ? 0 : rc.optInt("availableCountAfter", 0);
            resetCount = after;
            usagePercent = 0.0;
            return after;
        } catch (Exception e) {
            throw new ApiException(0, "parse reset result: " + e.getMessage());
        }
    }

    public void setModel(String optionId) throws ApiException {
        if (optionId.equals(selectedModel)) return;
        api("PUT", "/v1/pricing/model-option",
                "{\"optionId\":\"" + optionId + "\"}", 30);
        selectedModel = optionId;
    }

    /** 发消息，返回 threadId。 */
    public String send(String text) throws ApiException {
        JSONObject o = new JSONObject();
        try {
            o.put("message", text);
            o.put("clientMessageId", "msg_" + UUID.randomUUID().toString().replace("-", ""));
        } catch (Exception ignore) {}
        String body = api("POST", "/v1/messages", o.toString(), 60);
        try {
            JSONObject r = new JSONObject(body);
            String tid = r.optString("threadId", null);
            if (tid == null) throw new ApiException(0, "no threadId: " + trim(body));
            return tid;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "parse send: " + e.getMessage());
        }
    }

    /**
     * 轮询直到该 thread 的 assistant 消息 final。
     * 对应 core.py 的 wait_reply：按 threadId 过滤，取最后一条完整文本。
     */
    public String waitReply(String threadId, int timeoutSec) throws ApiException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                String body = api("GET", "/v1/conversations/messages?isMainChat=true&limit=50", null, 30);
                JSONObject root = new JSONObject(body);
                JSONObject data = root.optJSONObject("data");
                JSONArray items = data == null ? null : data.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject m = items.optJSONObject(i);
                        if (m == null) continue;
                        if (!threadId.equals(m.optString("threadId"))) continue;
                        if (!"assistant".equals(m.optString("role"))) continue;
                        String txt = textOf(m);
                        if (txt != null && !txt.isEmpty()) last = txt;
                        if ("completed".equals(m.optString("state")) && last != null && !last.isEmpty()) {
                            return last;
                        }
                    }
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(0, "parse messages: " + e.getMessage());
            }
            try { Thread.sleep(700); } catch (InterruptedException e) { break; }
        }
        if (last != null && !last.isEmpty()) return last;
        throw new ApiException(504, "upstream did not answer within " + timeoutSec + "s");
    }

    /** 提取 body.parts 里 messageType=text 的内容。 */
    private static String textOf(JSONObject msg) {
        try {
            JSONObject body = msg.optJSONObject("body");
            JSONArray parts = body == null ? null : body.optJSONArray("parts");
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null) continue;
                if (!"text".equals(p.optString("messageType"))) continue;
                JSONObject c = p.optJSONObject("content");
                if (c != null) sb.append(c.optString("text", ""));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 记账
    public void reward() {
        okCount++;
        fails = 0;
        cooldownUntil = 0;
        lastError = "";
    }

    public void penalize(ApiException e) {
        errCount++;
        fails++;
        lastError = e.getMessage() == null ? "error" : trim(e.getMessage());
        long now = System.currentTimeMillis();
        if (e.code == 401 || e.code == 403) {
            cooldownUntil = now + Pool.COOLDOWN_AUTH * 1000L;
        } else {
            long cd = Pool.COOLDOWN_TRANSIENT * 1000L * (1L << Math.min(fails - 1, 5));
            if (cd > Pool.COOLDOWN_MAX * 1000L) cd = Pool.COOLDOWN_MAX * 1000L;
            cooldownUntil = now + cd;
        }
    }

    // ------------------------------------------------------------------ 工具
    static String read(InputStream in) {
        if (in == null) return "";
        try (InputStream i = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String trim(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    public boolean sameToken(String t) {
        return token != null && token.equals(t);
    }
}
