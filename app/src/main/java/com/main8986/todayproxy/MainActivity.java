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
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        buildUi();
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

    private void startServiceCompat() {
        Intent i = new Intent(this, ProxyService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        ui.postDelayed(this::refresh, 800);
    }

    private String connText() {
        String ip = localIp();
        int port = store.port();
        return "OpenAI    http://" + ip + ":" + port + "/v1\n"
             + "Anthropic http://" + ip + ":" + port + "\n"
             + "API Key   " + store.apiKey();
    }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0)
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignore) {}
        return "127.0.0.1";
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
            }
        }
        logView.setText(store.readLog());
    }

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
        return box;
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
