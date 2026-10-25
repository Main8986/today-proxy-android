package com.main8986.todayproxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 号池：加权轮询 + 额度感知 + 故障冷却 + 并发排队。
 *
 * 移植自 today-proxy/pool.py，包含踩过的四个坑：
 *  1. 不能用"负载低优先"——并发 1 时 lane 被占就整号剔除，权重会摊平成均分。
 *  2. 必须 smooth WRR + 按模型分 lane，否则同号并发跑 deepseek/glm 会串味
 *     （上游模型选择是账号级状态）。
 *  3. 高权重号只是"忙"时要让低权重号先挂起一会儿，否则并发一高权重就失灵。
 *  4. 全忙要排队，不能立刻 503。
 */
public class Pool {

    public static final double MAX_USAGE = 98.0;
    public static final int COOLDOWN_AUTH = 600;
    public static final int COOLDOWN_TRANSIENT = 30;
    public static final int COOLDOWN_MAX = 900;
    public static final int MAX_ATTEMPTS = 3;
    public static final long QUEUE_WAIT_MS = 120_000L;
    public static final long HOLD_WAIT_MS = 1_500L;
    public static final int LANE_CONCURRENCY = 1;

    public static class NoAccountException extends ApiException {
        public NoAccountException(String m) { super(503, m); }
    }

    /** 简单的可重入 lane 锁（带超时获取 + 可探测是否被占）。 */
    public static final class LaneLock {
        private boolean held;
        public synchronized boolean acquire(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (held) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try { wait(Math.min(left, 100)); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            held = true;
            return true;
        }
        public synchronized void release() {
            held = false;
            notifyAll();
        }
        public synchronized boolean isHeld() { return held; }
    }

    public static class Pick {
        public final Account account;
        public final LaneLock lane;
        Pick(Account a, LaneLock l) { account = a; lane = l; }
    }

    private final List<Account> accounts = new ArrayList<>();
    private final Map<String, LaneLock> laneLocks = new HashMap<>();
    private final Map<String, Long> holdUsed = new HashMap<>();
    private final Map<Account, Double> currentWeight = new HashMap<>();
    private final Object lock = new Object();

    public List<Account> accounts() {
        synchronized (lock) { return new ArrayList<>(accounts); }
    }

    public int size() {
        synchronized (lock) { return accounts.size(); }
    }

    public boolean add(Account a) {
        synchronized (lock) {
            for (Account x : accounts) if (x.sameToken(a.token)) return false;
            accounts.add(a);
            return true;
        }
    }

    public List<Account> remove(String key) {
        List<Account> gone = new ArrayList<>();
        synchronized (lock) {
            for (int i = accounts.size() - 1; i >= 0; i--) {
                Account a = accounts.get(i);
                if ((a.label != null && a.label.equals(key)) || a.token.startsWith(key)) {
                    gone.add(a);
                    accounts.remove(i);
                    currentWeight.remove(a);
                }
            }
        }
        return gone;
    }

    public void clear() {
        synchronized (lock) { accounts.clear(); currentWeight.clear(); }
    }

    private LaneLock laneLock(int idx, String model) {
        String k = idx + "|" + model;
        synchronized (lock) {
            LaneLock o = laneLocks.get(k);
            if (o == null) { o = new LaneLock(); laneLocks.put(k, o); }
            return o;
        }
    }

    /** 选号。waitMs > 0 时全忙会排队等，不立刻失败。 */
    public Pick pick(String model, Set<String> tried, long waitMs) throws NoAccountException {
        final Set<String> skip = (tried == null) ? new HashSet<String>() : tried;
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);

        while (true) {
            long now = System.currentTimeMillis();
            List<Account> snap = accounts();
            List<Account> cands = new ArrayList<>();
            List<LaneLock> candLocks = new ArrayList<>();
            double busyMaxWeight = 0;

            for (int i = 0; i < snap.size(); i++) {
                Account a = snap.get(i);
                if (skip.contains(a.token)) continue;
                if (now < a.cooldownUntil) { currentWeight.put(a, 0.0); continue; }
                if (a.usagePercent != null && a.usagePercent >= MAX_USAGE) {
                    currentWeight.put(a, 0.0);
                    continue;
                }
                LaneLock lk = laneLock(i, model);
                if (lk.isHeld()) {
                    busyMaxWeight = Math.max(busyMaxWeight, a.weight);
                    continue;
                }
                cands.add(a);
                candLocks.add(lk);
            }

            if (!cands.isEmpty()) {
                // 高权重号只是忙 -> 挂起一会儿，别把单子派给低权重号
                if (skip.isEmpty() && waitMs > 0 && busyMaxWeight > 0) {
                    double idleMax = 0;
                    for (Account a : cands) idleMax = Math.max(idleMax, a.weight);
                    if (busyMaxWeight > idleMax) {
                        String key = model + "|" + busyMaxWeight;
                        Long used = holdUsed.get(key);
                        long u = used == null ? 0L : used;
                        if (u < HOLD_WAIT_MS && now < deadline) {
                            holdUsed.put(key, u + 50);
                            sleep(50);
                            continue;
                        }
                        holdUsed.put(key, 0L);
                    }
                }
                // smooth weighted round-robin
                double total = 0;
                for (Account a : cands) total += a.weight;
                Account best = null;
                double bestCw = -Double.MAX_VALUE;
                for (Account a : cands) {
                    Double prev = currentWeight.get(a);
                    double cw = (prev == null ? 0 : prev) + a.weight;
                    currentWeight.put(a, cw);
                    if (cw > bestCw) { bestCw = cw; best = a; }
                }
                int idx = cands.indexOf(best);
                currentWeight.put(best, currentWeight.get(best) - total);
                return new Pick(best, candLocks.get(idx));
            }

            if (System.currentTimeMillis() >= deadline) {
                throw new NoAccountException("no usable account for model=" + model
                        + " (cooldown/exhausted/busy)");
            }
            sleep(250);
        }
    }

    /** 池里跑一次对话：选号 → 切模型 → 发送 → 轮询，失败自动换号重试。 */
    public Result run(String modelName, String prompt, int timeoutSec) throws ApiException {
        String opt = TodayApi.normalizeModel(modelName);
        Set<String> tried = new HashSet<>();
        ApiException lastErr = null;
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        int attempts = Math.min(MAX_ATTEMPTS, Math.max(1, size()));

        for (int n = 0; n < attempts; n++) {
            Pick p = pick(opt, tried, QUEUE_WAIT_MS);
            Account a = p.account;
            long left = deadline - System.currentTimeMillis();
            if (!p.lane.acquire(Math.max(1000, Math.min(left, 300_000)))) {
                tried.add(a.token);
                continue;
            }
            a.inflight++;
            try {
                if (!opt.equals(a.selectedModel)) {
                    a.setModel(opt);
                }
                String tid = a.send(prompt);
                int remain = (int) Math.max(1, (deadline - System.currentTimeMillis()) / 1000);
                String reply = a.waitReply(tid, remain);
                a.reward();
                return new Result(a, tid, reply);
            } catch (ApiException e) {
                lastErr = e;
                a.penalize(e);
                tried.add(a.token);
            } finally {
                a.inflight--;
                p.lane.release();
            }
        }
        if (lastErr != null) throw lastErr;
        throw new NoAccountException("all accounts failed");
    }

    public static class Result {
        public final Account account;
        public final String threadId;
        public final String text;
        Result(Account a, String t, String x) { account = a; threadId = t; text = x; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
