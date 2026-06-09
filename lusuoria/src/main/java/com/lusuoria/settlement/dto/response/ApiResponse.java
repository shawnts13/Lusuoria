package com.lusuoria.settlement.dto.response;

import lombok.Data;

@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<T>();
        resp.setCode(200);
        resp.setMessage("success");
        resp.setData(data);
        return resp;
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<T>();
        resp.setCode(code);
        resp.setMessage(message);
        return resp;
    }

    public static <T> ApiResponse<T> error(String message) {
        return error(500, message);
    }
}