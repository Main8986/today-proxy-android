package com.main8986.todayproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 迷你 HTTP 服务：OpenAI + Anthropic 双协议 + SSE。
 * 对应 today-proxy/server.py，但只保留客户端需要的接口（没有 /ui，界面在 Activity 里）。
 */
public class ProxyServer {

    public interface Log { void line(String s); }

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final Pool pool;
    private final String authKey;      // 客户端 API Key，空 = 不鉴权
    private final int port;
    private final Log log;

    private ServerSocket server;
    private volatile boolean running;
    private final ExecutorService workers = Executors.newCachedThreadPool();

    public ProxyServer(Pool pool, String authKey, int port, Log log) {
        this.pool = pool;
        this.authKey = authKey == null ? "" : authKey.trim();
        this.port = port;
        this.log = log;
    }

    public int port() { return port; }
    public boolean isRunning() { return running; }

    public void start() throws Exception {
        server = new ServerSocket(port);
        running = true;
        workers.execute(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        workers.execute(new Runnable() {
                            @Override public void run() { handle(s); }
                        });
                    } catch (Exception e) {
                        if (running) ProxyServer.this.log.line("accept error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignore) {}
        server = null;
    }

    // ------------------------------------------------------------------ 请求处理
    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(300_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) { sock.close(); return; }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) { sock.close(); return; }
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                                       line.substring(c + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(headers.containsKey("content-length")
                    ? headers.get("content-length") : "0"); } catch (Exception ignore) {}
            byte[] bodyBytes = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(bodyBytes, off, len - off);
                if (n <= 0) break;
                off += n;
            }
            String body = new String(bodyBytes, 0, off, StandardCharsets.UTF_8);

            if ("OPTIONS".equals(method)) { sendEmpty(out); sock.close(); return; }

            String auth = headers.containsKey("authorization") ? headers.get("authorization") : "";
            if (auth.toLowerCase(Locale.US).startsWith("bearer ")) auth = auth.substring(7).trim();
            else auth = headers.containsKey("x-api-key") ? headers.get("x-api-key") : "";

            if (path.equals("/health") || path.equals("/healthz")) {
                sendJson(out, 200, health());
            } else if (path.equals("/v1/models")) {
                sendJson(out, 200, models());
            } else if (path.equals("/v1/chat/completions") || path.equals("/v1/messages")
                    || path.equals("/v1/complete")) {
                if (!authKey.isEmpty() && !authKey.equals(auth)) {
                    sendJson(out, 401, err("invalid api key", "authentication_error"));
                } else if (!"POST".equals(method)) {
                    sendJson(out, 405, err("method not allowed", "invalid_request_error"));
                } else {
                    handleChat(out, path, body);
                }
            } else {
                sendJson(out, 404, err("not found: " + path, "not_found"));
            }
            sock.close();
        } catch (Exception e) {
            ProxyServer.this.log.line("handle error: " + e.getClass().getSimpleName() + " " + e.getMessage());
            try { sock.close(); } catch (Exception ignore) {}
        }
    }

    private void handleChat(OutputStream out, String path, String body) throws Exception {
        JSONObject req;
        try { req = new JSONObject(body == null || body.isEmpty() ? "{}" : body); }
        catch (Exception e) { sendJson(out, 400, err("invalid JSON body", "invalid_request_error")); return; }

        String model = req.optString("model", "deepseek");
        boolean stream = req.optBoolean("stream", false);
        boolean anthropic = !path.equals("/v1/chat/completions");
        boolean legacy = path.equals("/v1/complete");

        String prompt;
        try { prompt = buildPrompt(req, anthropic, legacy); }
        catch (Exception e) { sendJson(out, 400, err(e.getMessage(), "invalid_request_error")); return; }
        if (prompt.trim().isEmpty()) {
            sendJson(out, 400, err("empty prompt", "invalid_request_error")); return;
        }

        // 模型名先校验，不认识就 400 —— 别静默换成 deepseek 让人误判
        try {
            TodayApi.normalizeModel(model);
        } catch (IllegalArgumentException e) {
            sendJson(out, 400, err(e.getMessage(), "invalid_request_error"));
            return;
        }

        long t0 = System.currentTimeMillis();
        Pool.Result r;
        try {
            r = pool.run(model, prompt, 180);
        } catch (ApiException e) {
            int code = (e.code >= 400 && e.code < 600) ? e.code : 502;
            sendJson(out, code, err(e.getMessage(), "upstream_error"));
            ProxyServer.this.log.line("! " + model + " -> " + code + " " + Account.trim(e.getMessage()));
            return;
        }
        long ms = System.currentTimeMillis() - t0;
        ProxyServer.this.log.line(String.format(Locale.US, "OK %s via %s (%dms, %d字)", model, r.account.label, ms, r.text.length()));

        String cid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = System.currentTimeMillis() / 1000;
        int outTokens = r.text.length();

        if (!stream) {
            JSONObject o = new JSONObject();
            if (anthropic && !legacy) {
                o.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                o.put("type", "message");
                o.put("role", "assistant");
                o.put("model", model);
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", r.text));
                o.put("content", content);
                o.put("stop_reason", "end_turn");
                o.put("stop_sequence", JSONObject.NULL);
                o.put("usage", new JSONObject().put("input_tokens", prompt.length())
                                               .put("output_tokens", outTokens));
                o.put("thread_id", r.threadId);
                o.put("account", r.account.label);
            } else if (legacy) {
                o.put("completion", "\n" + r.text);
                o.put("stop_reason", "stop");
                o.put("model", model);
            } else {
                o.put("id", cid);
                o.put("object", "chat.completion");
                o.put("created", created);
                o.put("model", model);
                o.put("thread_id", r.threadId);
                o.put("account", r.account.label);
                JSONObject msg = new JSONObject().put("role", "assistant").put("content", r.text);
                JSONArray ch = new JSONArray();
                ch.put(new JSONObject().put("index", 0).put("message", msg)
                                       .put("finish_reason", "stop"));
                o.put("choices", ch);
                o.put("usage", new JSONObject().put("prompt_tokens", prompt.length())
                                               .put("completion_tokens", outTokens)
                                               .put("total_tokens", prompt.length() + outTokens));
            }
            sendJson(out, 200, o);
            return;
        }

        // ---------------- SSE ----------------
        writeHead(out, 200, "text/event-stream; charset=utf-8");
        if (anthropic) anthropicStream(out, model, r, prompt, outTokens);
        else openaiStream(out, model, cid, created, r, outTokens);
        out.flush();
    }

    private void openaiStream(OutputStream out, String model, String cid, long created,
                              Pool.Result r, int outTokens) throws Exception {
        chunk(out, new JSONObject().put("id", cid).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model)
                .put("choices", one(new JSONObject().put("index", 0)
                        .put("delta", new JSONObject().put("role", "assistant").put("content", ""))
                        .put("finish_reason", JSONObject.NULL))));
        chunk(out, new JSONObject().put("id", cid).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model)
                .put("choices", one(new JSONObject().put("index", 0)
                        .put("delta", new JSONObject().put("content", r.text))
                        .put("finish_reason", JSONObject.NULL))));
        chunk(out, new JSONObject().put("id", cid).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model)
                .put("choices", one(new JSONObject().put("index", 0)
                        .put("delta", new JSONObject())
                        .put("finish_reason", "stop"))));
        writeChunk(out, "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        endChunked(out);
    }

    private static JSONArray one(JSONObject o) throws Exception {
        JSONArray a = new JSONArray();
        a.put(o);
        return a;
    }

    /**
     * 按 HTTP/1.1 chunked 格式写一帧 SSE。
     *
     * 坑：writeHead 已经声明了 Transfer-Encoding: chunked，但这里如果只写裸数据，
     * 客户端会把 "data: {...}" 里的字符当成 chunk 长度去解析，直接报 Stream error。
     * 每一帧必须是  "<十六进制长度>\r\n<内容>\r\n"  的形式。
     */
    private void chunk(OutputStream out, JSONObject o) throws Exception {
        writeChunk(out, ("data: " + o.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeChunk(OutputStream out, byte[] payload) throws Exception {
        out.write(Integer.toHexString(payload.length).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(payload);
        out.write(CRLF);
        out.flush();
    }

    /** chunked 结束标记 */
    private static void endChunked(OutputStream out) throws Exception {
        out.write(LAST_CHUNK);
        out.flush();
    }

    private void anthropicStream(OutputStream out, String model, Pool.Result r,
                                 String prompt, int outTokens) throws Exception {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        sse(out, "message_start", new JSONObject().put("type", "message_start")
                .put("message", new JSONObject().put("id", id).put("type", "message")
                        .put("role", "assistant").put("model", model)
                        .put("content", new JSONArray())
                        .put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
                        .put("usage", new JSONObject().put("input_tokens", prompt.length())
                                                     .put("output_tokens", 0))));
        sse(out, "content_block_start", new JSONObject().put("type", "content_block_start")
                .put("index", 0)
                .put("content_block", new JSONObject().put("type", "text").put("text", "")));
        sse(out, "content_block_delta", new JSONObject().put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", new JSONObject().put("type", "text_delta").put("text", r.text)));
        sse(out, "content_block_stop", new JSONObject().put("type", "content_block_stop").put("index", 0));
        sse(out, "message_delta", new JSONObject().put("type", "message_delta")
                .put("delta", new JSONObject().put("stop_reason", "end_turn")
                                             .put("stop_sequence", JSONObject.NULL))
                .put("usage", new JSONObject().put("output_tokens", outTokens)));
        sse(out, "message_stop", new JSONObject().put("type", "message_stop"));
        endChunked(out);
    }

    private void sse(OutputStream out, String event, JSONObject data) throws Exception {
        writeChunk(out, ("event: " + event + "\ndata: " + data.toString() + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 提示词整形
    private static String buildPrompt(JSONObject req, boolean anthropic, boolean legacy) {
        if (legacy) return req.optString("prompt", "");
        List<String> system = new ArrayList<>();
        List<JSONObject> turns = new ArrayList<>();
        if (anthropic) {
            if (req.has("system")) system.add(asText(req.opt("system")));
            JSONArray msgs = req.optJSONArray("messages");
            if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m != null) turns.add(m);
            }
        } else {
            JSONArray msgs = req.optJSONArray("messages");
            if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                if ("system".equals(role)) system.add(asText(m.opt("content")));
                else turns.add(m);
            }
        }
        if (turns.isEmpty()) return join(system, "\n");
        if (turns.size() == 1 && system.isEmpty()) return asText(turns.get(0).opt("content"));

        StringBuilder sb = new StringBuilder();
        if (!system.isEmpty()) sb.append("[系统指令]\n").append(join(system, "\n\n"));
        if (turns.size() > 1) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[历史对话]");
            for (int i = 0; i < turns.size() - 1; i++) {
                JSONObject m = turns.get(i);
                sb.append("\n").append("user".equals(m.optString("role")) ? "用户" : "助手")
                  .append("：").append(asText(m.opt("content")));
            }
            sb.append("\n[当前消息]");
        }
        if (sb.length() > 0) sb.append("\n");
        sb.append(asText(turns.get(turns.size() - 1).opt("content")));
        return sb.toString();
    }

    private static String asText(Object c) {
        if (c == null || c == JSONObject.NULL) return "";
        if (c instanceof String) return (String) c;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) c;
            for (int i = 0; i < a.length(); i++) {
                Object o = a.opt(i);
                if (o instanceof JSONObject) {
                    JSONObject jo = (JSONObject) o;
                    if ("text".equals(jo.optString("type"))) sb.append(jo.optString("text", ""));
                } else if (o instanceof String) {
                    sb.append(o);
                }
            }
            return sb.toString();
        }
        return String.valueOf(c);
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 响应
    private JSONObject health() throws Exception {
        List<Account> accs = pool.accounts();
        long now = System.currentTimeMillis();
        int healthy = 0;
        JSONArray arr = new JSONArray();
        for (Account a : accs) {
            boolean h = a.healthy(now);
            if (h) healthy++;
            arr.put(new JSONObject().put("label", a.label).put("healthy", h)
                    .put("usage", a.usagePercent == null ? JSONObject.NULL : a.usagePercent)
                    .put("cooldown", a.cooldownLeft(now)).put("inflight", a.inflight));
        }
        return new JSONObject().put("ok", healthy > 0).put("accounts", accs.size())
                .put("healthy", healthy).put("auth_required", !authKey.isEmpty())
                .put("running", port).put("pool", arr);
    }

    private JSONObject models() throws Exception {
        JSONArray data = new JSONArray();
        data.put(new JSONObject().put("id", "deepseek-v4.1-flash").put("object", "model")
                .put("owned_by", "today"));
        data.put(new JSONObject().put("id", "model.deepseek-v4.1-flash").put("object", "model")
                .put("owned_by", "today"));
        data.put(new JSONObject().put("id", "glm-5.3").put("object", "model").put("owned_by", "today"));
        data.put(new JSONObject().put("id", "model.glm-5.3").put("object", "model").put("owned_by", "today"));
        return new JSONObject().put("object", "list").put("data", data);
    }

    private static JSONObject err(String msg, String type) {
        // JSONObject.put 会抛受检的 JSONException。这里内部吞掉，
        // 否则 15 个调用点全部要加 throws，得不偿失。
        JSONObject o = new JSONObject();
        try {
            JSONObject e = new JSONObject();
            e.put("message", msg == null ? "" : msg);
            e.put("type", type);
            o.put("error", e);
        } catch (Exception ignore) {
        }
        return o;
    }

    private void sendJson(OutputStream out, int code, JSONObject o) {
        try {
            byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
            writeHead(out, code, "application/json; charset=utf-8", b.length);
            out.write(b);
            out.flush();
        } catch (Exception ignore) {}
    }

    private void writeHead(OutputStream out, int code, String ctype) throws Exception {
        writeHead(out, code, ctype, -1);
    }

    private void writeHead(OutputStream out, int code, String ctype, int len) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Content-Type: ").append(ctype).append("\r\n");
        boolean isStream = ctype != null && ctype.startsWith("text/event-stream");
        if (len >= 0) {
            sb.append("Content-Length: ").append(len).append("\r\n");
        } else {
            sb.append("Transfer-Encoding: chunked\r\n");
            sb.append("X-Accel-Buffering: no\r\n");
        }
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, x-api-key, anthropic-version\r\n");
        // 流式不能声明 Connection: close，否则部分客户端认为是"读到断连为止"，
        // 反而忽略了 chunked 边界，一直等到超时才报错。
        if (!isStream) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendEmpty(OutputStream out) {
        try {
            writeHead(out, 204, "text/plain", 0);
            out.flush();
        } catch (Exception ignore) {}
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Error";
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
        }
        if (c == -1 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
