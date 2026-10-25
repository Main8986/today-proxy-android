package com.main8986.todayproxy;

/**
 * 上游协议层：Today 后端的认证链与对话接口。
 *
 * 这个文件是 today-proxy（https://github.com/Main8986/today-proxy）里 core.py 的
 * Java 移植版，逻辑一一对应：
 *
 *   session token ──GET /api/auth/token──> ES256 JWT(~115s) ──Bearer──> api.todayai.cn/v1/*
 *
 * 之所以在手机上要单独实现而不是打包 Python：Android 上没有现成的 CPython 运行时，
 * 塞 Chaquopy 会让 APK 从 2MB 涨到 60MB+，不划算。
 */
public final class TodayApi {

    public static final String AUTH_BASE = "https://auth.todayai.cn";
    public static final String API_BASE = "https://api.todayai.cn";
    public static final String APP_VERSION = "1.23.0";

    public static final String OPT_DEEPSEEK = "model.deepseek-v4.1-flash";
    public static final String OPT_GLM = "model.glm-5.3";

    private static final String CAPS =
            "chat.browser.v1,connectors.account_management.v1,chat.brief_card.v1,"
          + "chat.qa_cardflow_single.v1,chat.health_metric_card.v1,chat.automation_card.v1,"
          + "chat.follow_up_recommendations.v1,chat.message_reactions.unicode.v1";

    private TodayApi() {}

    /** 把 OpenAI 风格的模型名归一到上游 optionId；不认识就返回 default。 */
    public static String normalizeModel(String name) {
        if (name == null) return OPT_DEEPSEEK;
        String s = name.trim().toLowerCase();
        if (s.equals("glm") || s.equals("glm-5.3") || s.equals("model.glm-5.3")) return OPT_GLM;
        if (s.equals("deepseek") || s.equals("deepseek-v4.1-flash")
                || s.equals("model.deepseek-v4.1-flash")) return OPT_DEEPSEEK;
        return OPT_DEEPSEEK;
    }

    /** OpenAI 暴露的模型短名（给 /v1/models 用）。 */
    public static String shortName(String optionId) {
        if (OPT_GLM.equals(optionId)) return "glm-5.3";
        return "deepseek-v4.1-flash";
    }

    public static String userAgent(String deviceId) {
        return "Today/" + APP_VERSION + " Version/" + APP_VERSION
             + " Build/2768 Device/(OnePlus;OP5D2BL1)(PKG110; Android 15) NetType/WiFi";
    }

    public static String capabilities() { return CAPS; }
}
