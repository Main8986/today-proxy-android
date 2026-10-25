package com.main8986.todayproxy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 配置持久化：账号列表（token/权重/标签）、API Key、端口、默认模型。 */
public class Store {

    private static final String PREF = "today_proxy";
    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- API Key ----------------
    public String apiKey() {
        String k = sp.getString("api_key", null);
        if (k == null || k.isEmpty()) {
            k = "sk-today-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            sp.edit().putString("api_key", k).apply();
        }
        return k;
    }

    public void setApiKey(String k) { sp.edit().putString("api_key", k).apply(); }

    // ---------------- 端口 ----------------
    public int port() { return sp.getInt("port", 8770); }
    public void setPort(int p) { sp.edit().putInt("port", p).apply(); }

    // ---------------- 默认模型 ----------------
    public String defaultModel() { return sp.getString("default_model", "deepseek"); }
    public void setDefaultModel(String m) { sp.edit().putString("default_model", m).apply(); }

    public boolean autoStart() { return sp.getBoolean("auto_start", true); }
    public void setAutoStart(boolean b) { sp.edit().putBoolean("auto_start", b).apply(); }

    // ---------------- 账号 ----------------
    public static class Entry {
        public String token;
        public String label;
        public double weight;
        Entry(String t, String l, double w) { token = t; label = l; weight = w; }
    }

    public List<Entry> loadAccounts() {
        List<Entry> out = new ArrayList<>();
        String raw = sp.getString("accounts", "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("token", "");
                if (t.isEmpty()) continue;
                out.add(new Entry(t, o.optString("label", ""), o.optDouble("weight", 1.0)));
            }
        } catch (Exception ignore) {}
        return out;
    }

    public void saveAccounts(List<Account> accounts) {
        JSONArray a = new JSONArray();
        for (Account acc : accounts) {
            try {
                a.put(new JSONObject().put("token", acc.token)
                        .put("label", acc.label == null ? "" : acc.label)
                        .put("weight", acc.weight));
            } catch (Exception ignore) {}
        }
        sp.edit().putString("accounts", a.toString()).apply();
    }

    public boolean addAccount(Account acc) {
        List<Entry> cur = loadAccounts();
        for (Entry e : cur) if (e.token.equals(acc.token)) return false;
        JSONArray a = new JSONArray();
        for (Entry e : cur) {
            try {
                a.put(new JSONObject().put("token", e.token).put("label", e.label)
                        .put("weight", e.weight));
            } catch (Exception ignore) {}
        }
        try {
            a.put(new JSONObject().put("token", acc.token)
                    .put("label", acc.label == null ? "" : acc.label)
                    .put("weight", acc.weight));
        } catch (Exception ignore) {}
        sp.edit().putString("accounts", a.toString()).apply();
        return true;
    }

    public void removeAccount(String token) {
        List<Entry> cur = loadAccounts();
        JSONArray a = new JSONArray();
        for (Entry e : cur) {
            if (e.token.equals(token)) continue;
            try {
                a.put(new JSONObject().put("token", e.token).put("label", e.label)
                        .put("weight", e.weight));
            } catch (Exception ignore) {}
        }
        sp.edit().putString("accounts", a.toString()).apply();
    }

    // ---------------- 日志 ----------------
    public void appendLog(String line) {
        String old = sp.getString("log", "");
        String merged = line + "\n" + old;
        if (merged.length() > 20000) merged = merged.substring(0, 20000);
        sp.edit().putString("log", merged).apply();
    }

    public String readLog() { return sp.getString("log", ""); }
    public void clearLog() { sp.edit().putString("log", "").apply(); }
}
