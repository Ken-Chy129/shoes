package cn.ken.shoes.common;

import lombok.Data;

import java.util.Collections;


@Data
public class Result<T> {

    private Integer code;

    private String msg;

    private T data;

    public static <T> Result<T> buildSuccess() {
        return new Result<>();
    }

    public static <T> Result<T> buildSuccess(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> buildError() {
        Result<T> result = new Result<>();
        result.setCode(500);
        return result;
    }

    public static <T> Result<T> buildError(String msg) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMsg(msg);
        return result;
    }

}
