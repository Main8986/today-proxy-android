package com.main8986.todayproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * 主界面：全部用代码构建（不依赖 XML 布局和 AppCompat），
 * 这样只要 android.jar + 一个 dex 就能编出来，APK 保持 <100KB。
 */
public class MainActivity extends Activity {

    private static final int C_BG = 0xFF111214;
    private static final int C_CARD = 0xFF191B1F;
    private static final int C_LINE = 0xFF26282D;
    private static final int C_TXT = 0xFFE6E6E6;
    private static final int C_DIM = 0xFF7A7F87;
    private static final int C_ACC = 0xFF3B6CF6;
    private static final int C_OK = 0xFF4EC78F;
    private static final int C_BAD = 0xFFF06262;

    private Store store;
    private LinearLayout root;
    private TextView statusView;
    private LinearLayout poolBox;
    private TextView logView;
    private Button toggleBtn;
    private TextView wifiHint;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        buildUi();
        requestNotifPermission();
        refresh();
        ui.postDelayed(tick, 2000);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, dp(48));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        TextView title = text("today-proxy", 20, C_TXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("本地 AI 反代 · OpenAI / Anthropic 双协议", 12, C_DIM));

        // ---- 状态卡 ----
        LinearLayout card = card();
        card.addView(label("运行状态"));
        statusView = text("未启动", 14, C_TXT);
        card.addView(statusView);
        toggleBtn = new Button(this);
        toggleBtn.setText("启动服务");
        toggleBtn.setAllCaps(false);
        toggleBtn.setOnClickListener(v -> {
            if (ProxyService.isRunning()) {
                stopService(new Intent(this, ProxyService.class));
                ui.postDelayed(this::refresh, 400);
            } else {
                startServiceCompat();
            }
        });
        card.addView(toggleBtn);
        root.addView(card);

        // ---- 接入信息 ----
        LinearLayout conn = card();
        conn.addView(label("客户端接入"));
        TextView kv = text(connText(), 12, C_TXT);
        kv.setTextIsSelectable(true);
        conn.addView(kv);
        wifiHint = text("⚠ 当前不在 WiFi 上，局域网地址（192.168.x.x）连不上。\n"
                + "要么连回 WiFi，要么客户端用 127.0.0.1。", 11, 0xFFE0B44F);
        wifiHint.setPadding(0, dp(8), 0, 0);
        conn.addView(wifiHint);
        Button copy = new Button(this);
        copy.setText("复制 API Key");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("key", store.apiKey()));
            toast("已复制");
        });
        conn.addView(copy);
        root.addView(conn);

        // ---- 号池 ----
        LinearLayout poolCard = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label("号池");
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = new Button(this);
        add.setText("＋ 加号");
        add.setAllCaps(false);
        add.setOnClickListener(v -> showAddDialog());
        head.addView(add);
        poolCard.addView(head);

        poolBox = new LinearLayout(this);
        poolBox.setOrientation(LinearLayout.VERTICAL);
        poolCard.addView(poolBox);
        root.addView(poolCard);

        // ---- 设置 ----
        LinearLayout set = card();
        set.addView(label("设置"));
        EditText portEt = input("端口", String.valueOf(store.port()), InputType.TYPE_CLASS_NUMBER);
        set.addView(portEt);
        Button savePort = new Button(this);
        savePort.setText("保存端口（重启生效）");
        savePort.setAllCaps(false);
        savePort.setOnClickListener(v -> {
            try {
                int np = Integer.parseInt(portEt.getText().toString().trim());
                if (np < 1024 || np > 65535) throw new NumberFormatException();
                store.setPort(np);
                toast("已保存，重启服务生效");
                refresh();
            } catch (Exception e) {
                toast("端口需在 1024-65535 之间");
            }
        });
        set.addView(savePort);

        CheckBox auto = new CheckBox(this);
        auto.setText("打开 APP 自动启动服务");
        auto.setTextColor(C_TXT);
        auto.setChecked(store.autoStart());
        auto.setOnCheckedChangeListener((v, c) -> store.setAutoStart(c));
        set.addView(auto);
        root.addView(set);

        // ---- 日志 ----
        LinearLayout logCard = card();
        LinearLayout lh = new LinearLayout(this);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.addView(label("日志"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clr = new Button(this);
        clr.setText("清空");
        clr.setAllCaps(false);
        clr.setOnClickListener(v -> { store.clearLog(); refresh(); });
        lh.addView(clr);
        logCard.addView(lh);
        logView = text("", 11, C_DIM);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logCard.addView(logView);
        root.addView(logCard);

        if (store.autoStart() && !ProxyService.isRunning()) {
            startServiceCompat();
        }
    }

    /** Android 13+ 必须运行时申请通知权限，否则前台服务的通知不显示。 */
    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
    }

    private void startServiceCompat() {
        Intent i = new Intent(this, ProxyService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            // Android 12+ 从后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException），
            // 这种情况引导用户把 APP 切到前台再点一次。
            toast("启动失败：" + e.getClass().getSimpleName() + "，请把 APP 保持在屏幕上再试");
        }
        ui.postDelayed(this::refresh, 800);
    }

    private String connText() {
        String ip = localIp();
        int port = store.port();
        StringBuilder sb = new StringBuilder();
        sb.append("【本机 App 用这个】同手机上的客户端\n")
          .append("OpenAI    http://127.0.0.1:").append(port).append("/v1\n")
          .append("Anthropic http://127.0.0.1:").append(port).append("\n\n");
        if (ip == null) {
            sb.append("【局域网其它设备用】\n")
              .append("当前没有 WiFi 连接，无局域网地址可用。\n")
              .append("要连别的设备请先连上 WiFi。\n");
        } else {
            sb.append("【局域网其它设备用】必须同一个 WiFi\n")
              .append("OpenAI    http://").append(ip).append(":").append(port).append("/v1\n")
              .append("Anthropic http://").append(ip).append(":").append(port).append("\n");
        }
        sb.append("\nAPI Key   ").append(store.apiKey());
        return sb.toString();
    }

    /** 是否连着 WiFi。不连 WiFi 时 192.168.x.x 那个地址是不可达的。 */
    private boolean onWifi() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return false;
            return ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return true;   // 判断不了就不提示，别吓人
        }
    }

    /**
     * 取真实局域网 IP。没有 WiFi 时返回 null。
     *
     * 坑：以前直接取第一个非回环 IPv4，手机上会先撞到
     *   tun0 (172.19.0.1)   VPN 虚拟网卡，外部设备根本连不上
     *   rmnet_data (10.x)   移动数据网，不属于任何局域网
     * 现在只认 wlan 开头的网卡，并且要求地址在私有网段。
     */
    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                // 只认 WiFi 网卡；排除 tun/rmnet/ppp 等虚拟或蜂窝网卡
                if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth"))) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    String ip = a.getHostAddress();
                    if (ip == null || ip.indexOf(':') >= 0) continue;
                    if (isPrivateIp(ip)) return ip;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** 10.x / 172.16-31.x / 192.168.x 才是可用的局域网地址。 */
    private static boolean isPrivateIp(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            if (a == 10) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
        } catch (NumberFormatException ignore) {}
        return false;
    }

    // ------------------------------------------------------------------ 刷新
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    private void refresh() {
        boolean run = ProxyService.isRunning();
        toggleBtn.setText(run ? "停止服务" : "启动服务");
        toggleBtn.setBackgroundColor(run ? 0xFF5A2E31 : C_ACC);

        Pool pool = ProxyService.pool();
        long now = System.currentTimeMillis();
        if (!run || pool == null) {
            statusView.setText("未启动");
            statusView.setTextColor(C_DIM);
            poolBox.removeAllViews();
            poolBox.addView(text("服务未运行", 12, C_DIM));
        } else {
            int healthy = 0;
            List<Account> accs = pool.accounts();
            for (Account a : accs) if (a.healthy(now)) healthy++;
            statusView.setText("运行中 · 端口 " + store.port() + " · 可用 "
                    + healthy + "/" + accs.size());
            statusView.setTextColor(healthy > 0 ? C_OK : C_BAD);

            poolBox.removeAllViews();
            if (accs.isEmpty()) {
                poolBox.addView(text("还没有账号，点右上角加号", 12, C_DIM));
            } else {
                for (Account a : accs) poolBox.addView(accountRow(a, now));
                // 兜底：哪个号还没拿到额度/次数，就补查一次（每号每 20 秒最多一次）
                for (Account a : accs) maybeFetchStatus(a);
            }
        }
        // 不在 WiFi 时，提示局域网地址不可用（连不上是常见坑）
        if (wifiHint != null) {
            wifiHint.setVisibility(onWifi() ? View.GONE : View.VISIBLE);
        }
        logView.setText(store.readLog());
    }

    /** 数据缺失时补查一次，避免界面一直显示 "—"。 */
    private void maybeFetchStatus(final Account a) {
        if (a.usagePercent != null && a.resetCount != null) return;
        long now = System.currentTimeMillis();
        Long last = lastFetch.get(a);
        if (last != null && now - last < 20000) return;
        lastFetch.put(a, now);
        new Thread(() -> {
            try {
                a.jwt();
                a.refreshStatus();
                a.reward();
            } catch (ApiException e) {
                a.penalize(e);
            }
            ui.post(this::refresh);
        }).start();
    }

    private final java.util.Map<Account, Long> lastFetch = new java.util.HashMap<>();

    private View accountRow(Account a, long now) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.healthy(now) ? 0xFF14161A : 0xFF1B1415);
        bg.setStroke(dp(1), a.healthy(now) ? C_LINE : 0xFF5A2E31);
        bg.setCornerRadius(dp(8));
        box.setBackground(bg);
        box.setPadding(dp(11), dp(9), dp(11), dp(9));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = text("●", 11, a.healthy(now) ? C_OK : C_BAD);
        top.addView(dot);
        TextView name = text(" " + (a.label == null || a.label.isEmpty() ? mask(a.token) : a.label),
                13, C_TXT);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(text("×" + fmt(a.weight), 11, C_DIM));
        Button del = new Button(this);
        del.setText("移除");
        del.setAllCaps(false);
        del.setTextSize(11);
        del.setOnClickListener(v -> {
            Pool p = ProxyService.pool();
            if (p != null) p.remove(a.label);
            store.removeAccount(a.token);
            refresh();
        });
        top.addView(del);
        box.addView(top);

        String meta = "额度 " + (a.usagePercent == null ? "—" : fmt(a.usagePercent) + "%")
                + " · " + (a.plan == null ? "—" : a.plan)
                + " · 在跑 " + a.inflight
                + " · 成功 " + a.okCount + " / 失败 " + a.errCount;
        if (now < a.cooldownUntil) meta += " · 冷却 " + a.cooldownLeft(now) + "s";
        TextView mv = text(meta, 11, C_DIM);
        box.addView(mv);
        if (a.lastError != null && !a.lastError.isEmpty()) {
            box.addView(text(a.lastError, 10, C_BAD));
        }

        // ---- 重置次数 + 重置按钮（每张号卡独立）----
        View divider = new View(this);
        divider.setBackgroundColor(0xFF22252A);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(8);
        dlp.bottomMargin = dp(7);
        box.addView(divider, dlp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int rc = a.resetCount == null ? -1 : a.resetCount;
        String rcText = rc < 0 ? "可重置 — 次" : "可重置 " + rc + " 次";
        TextView rcView = text(rcText, 11, rc > 0 ? 0xFFE0B44F : C_DIM);
        row.addView(rcView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button reset = new Button(this);
        reset.setText("立即重置");
        reset.setAllCaps(false);
        reset.setTextSize(11);
        reset.setEnabled(rc > 0);
        reset.setAlpha(rc > 0 ? 1f : 0.35f);
        final Account acc = a;
        final int left = rc;
        reset.setOnClickListener(v -> confirmReset(acc, left));
        row.addView(reset);
        box.addView(row);

        return box;
    }

    /** 重置需要手打 RESET 才执行 —— 一次点击就真的消耗一次配额。 */
    private void confirmReset(final Account acc, final int left) {
        if (left < 1) {
            toast("这个账号没有可用的重置次数");
            return;
        }
        final EditText et = input("输入大写 RESET", "", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
            .setTitle("⚠ 确认重置")
            .setMessage("账号：" + acc.label
                    + "\n当前次数：" + left + " 次"
                    + "\n重置后：" + (left - 1) + " 次"
                    + "\n\n重置会立即消耗 1 次配额，不可撤销。")
            .setView(et)
            .setPositiveButton("确认重置", (d, w) -> {
                if (!"RESET".equals(et.getText().toString().trim())) {
                    toast("已取消（需输入大写 RESET）");
                    return;
                }
                doReset(acc);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void doReset(final Account acc) {
        new Thread(() -> {
            try {
                int after = acc.consumeReset(null);
                ui.post(() -> {
                    toast("重置成功，剩余 " + after + " 次");
                    refresh();
                });
            } catch (ApiException e) {
                ui.post(() -> toast("重置失败：" + Account.trim(e.getMessage())));
            }
        }).start();
    }

    // ------------------------------------------------------------------ 加号
    private void showAddDialog() {
        final EditText tok = input("粘贴 session token（32 位）", "", InputType.TYPE_CLASS_TEXT);
        final EditText weight = input("权重，默认 1", "1",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText label = input("备注名（可空）", "", InputType.TYPE_CLASS_TEXT);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(tok);
        box.addView(weight);
        box.addView(label);

        new AlertDialog.Builder(this)
            .setTitle("加号")
            .setView(box)
            .setPositiveButton("加入", (d, w) -> {
                String t = tok.getText().toString().trim();
                if (t.isEmpty()) { toast("token 不能为空"); return; }
                double wt = 1;
                try { wt = Double.parseDouble(weight.getText().toString().trim()); }
                catch (Exception ignore) {}
                String lb = label.getText().toString().trim();
                if (lb.isEmpty()) lb = mask(t);
                Account acc = new Account(t, lb, wt);
                if (ProxyService.pool() != null) {
                    if (!ProxyService.pool().add(acc)) { toast("这个号已经在池里了"); return; }
                }
                store.addAccount(acc);
                Toast.makeText(this, "已加入，正在验证…", Toast.LENGTH_SHORT).show();
                verifyAsync(acc);
                refresh();
            })
            .setNegativeButton("短信登录", (d, w) -> showSmsDialog())
            .setNeutralButton("取消", null)
            .show();
    }

    private void showSmsDialog() {
        final EditText phone = input("手机号 +8613800000000", "+86", InputType.TYPE_CLASS_PHONE);
        final EditText code = input("验证码", "", InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(phone);
        Button send = new Button(this);
        send.setText("发送验证码");
        send.setAllCaps(false);
        send.setOnClickListener(v -> sendOtp(phone.getText().toString().trim()));
        box.addView(send);
        box.addView(code);

        new AlertDialog.Builder(this)
            .setTitle("短信登录")
            .setView(box)
            .setPositiveButton("登录并加入", (d, w) ->
                    verifyOtp(phone.getText().toString().trim(),
                              code.getText().toString().trim()))
            .setNegativeButton("取消", null)
            .show();
    }

    private void sendOtp(final String phone) {
        new Thread(() -> {
            try {
                String body = "{\"phoneNumber\":\"" + phone + "\"}";
                String resp = httpPost("https://auth.todayai.cn/api/auth/phone-number/send-otp", body);
                ui.post(() -> toast("已发送：" + Account.trim(resp)));
            } catch (Exception e) {
                ui.post(() -> toast("发送失败：" + e.getMessage()));
            }
        }).start();
    }

    private void verifyOtp(final String phone, final String code) {
        new Thread(() -> {
            try {
                String body = "{\"phoneNumber\":\"" + phone + "\",\"code\":\"" + code + "\"}";
                String resp = httpPost("https://auth.todayai.cn/api/auth/phone-number/verify", body);
                org.json.JSONObject o = new org.json.JSONObject(resp);
                String token = o.optString("token", null);
                if (token == null || token.isEmpty()) {
                    ui.post(() -> toast("登录失败：" + Account.trim(resp)));
                    return;
                }
                org.json.JSONObject user = o.optJSONObject("user");
                String label = user == null ? mask(token)
                        : (user.optString("phoneNumber", "").isEmpty()
                           ? mask(token) : user.optString("phoneNumber"));
                Account acc = new Account(token, label, 1.0);
                if (ProxyService.pool() != null && !ProxyService.pool().add(acc)) {
                    ui.post(() -> toast("这个号已在池里"));
                    return;
                }
                store.addAccount(acc);
                verifyAsync(acc);
                ui.post(() -> { toast("登录成功"); refresh(); });
            } catch (Exception e) {
                ui.post(() -> toast("登录失败：" + e.getMessage()));
            }
        }).start();
    }

    private void verifyAsync(final Account acc) {
        new Thread(() -> {
            try {
                acc.jwt();
                acc.refreshStatus();
                acc.reward();
            } catch (ApiException e) {
                acc.penalize(e);
            }
            ui.post(this::refresh);
        }).start();
    }

    private String httpPost(String url, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Origin", "https://todayai.cn");
        c.setRequestProperty("X-Client-Platform", "android");
        c.setRequestProperty("X-App-Version", TodayApi.APP_VERSION);
        c.setDoOutput(true);
        byte[] b = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(b.length);
        try (java.io.OutputStream os = c.getOutputStream()) { os.write(b); }
        int code = c.getResponseCode();
        java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String r = Account.read(is);
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + " " + Account.trim(r));
        return r;
    }

    // ------------------------------------------------------------------ 小工具
    private static String mask(String t) {
        if (t == null) return "?";
        return t.length() <= 6 ? t : ("acct_" + t.substring(t.length() - 6));
    }

    private static String fmt(double d) {
        return String.format(Locale.US, d == Math.rint(d) ? "%.0f" : "%.1f", d);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setStroke(dp(1), C_LINE);
        bg.setCornerRadius(dp(10));
        c.setBackground(bg);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, C_DIM);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private EditText input(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setTextSize(13);
        e.setTextColor(C_TXT);
        e.setHintTextColor(C_DIM);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
