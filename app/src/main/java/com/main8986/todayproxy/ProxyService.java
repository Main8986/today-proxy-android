package com.main8986.todayproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.List;

/**
 * 前台服务：把反代跑在通知栏里，避免系统回收。
 * Android 8+ 必须 isForegroundService，否则后台几秒就被杀。
 */
public class ProxyService extends Service implements ProxyServer.Log {

    public static final String CHANNEL = "today_proxy";
    public static final int NOTIF_ID = 0x7464;

    public static ProxyServer server;
    public static Pool pool;
    private Store store;
    private static Store sharedStore;

    public static boolean isRunning() {
        return server != null && server.isRunning();
    }

    public static Pool pool() { return pool; }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isRunning()) return START_STICKY;
        try {
            createChannel();
            // Android 14 要求显式声明前台服务类型，否则启动即崩。
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotif("正在启动…"),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, buildNotif("正在启动…"));
            }

            if (pool == null) pool = new Pool();
            pool.clear();
            List<Store.Entry> list = store.loadAccounts();
            for (Store.Entry e : list) {
                pool.add(new Account(e.token, e.label, e.weight));
            }
            server = new ProxyServer(pool, store.apiKey(), store.port(), this);
            server.start();
            updateNotif();
            line("服务已启动 :" + store.port() + " 账号 " + pool.size() + " 个");
        } catch (Exception e) {
            line("启动失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
            stopSelf();
        }
        return START_STICKY;
    }

    public void updateNotif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotif(describe()));
        } catch (Exception ignore) {}
    }

    private String describe() {
        if (pool == null) return "未运行";
        long now = System.currentTimeMillis();
        int healthy = 0;
        for (Account a : pool.accounts()) if (a.healthy(now)) healthy++;
        return "端口 " + store.port() + " · 可用 " + healthy + "/" + pool.size();
    }

    private Notification buildNotif(String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("today-proxy 运行中")
         .setContentText(text)
         .setSmallIcon(android.R.drawable.stat_sys_download_done)
         .setContentIntent(pi)
         .setOngoing(true);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "反代服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("本地 AI 反代运行状态");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            try { server.stop(); } catch (Exception ignore) {}
            server = null;
        }
        if (store != null) line("服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void line(String s) {
        if (store != null) store.appendLog(ts(s));
    }

    static String ts(String s) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + "  " + s;
    }

    public static Store store(Context ctx) {
        if (sharedStore == null) sharedStore = new Store(ctx);
        return sharedStore;
    }
}
