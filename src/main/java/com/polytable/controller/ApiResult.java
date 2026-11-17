package com.polytable.controller;

public record ApiResult<T>(String code, T data, String msg) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>("00000", data, "一切ok");
    }

    public static <T> ApiResult<T> fail(String code, String msg) {
        return new ApiResult<>(code, null, msg);
    }

    public static <T> ApiResult<T> fail(String code, String msg, T data) {
        return new ApiResult<>(code, data, msg);
    }
}
