package com.main8986.todayproxy;

/** 带 HTTP 状态码的异常，等价 py 版的 TodayError。 */
public class ApiException extends Exception {
    public final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }
}
