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
        result.setData(data);
        return result;
    }

}
