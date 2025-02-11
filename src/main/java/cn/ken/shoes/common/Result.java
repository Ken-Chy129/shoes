package cn.ken.shoes.common;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;


@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 2074303239886411976L;

    private Boolean success;

    private String errorCode;

    private String errorMsg;

    private T data;

    public Result() {
    }

    public Result(Boolean success) {
        this.success = success;
    }

    public static <T> Result<T> buildSuccess() {
        return new Result<>(true);
    }

    public static <T> Result<T> buildSuccess(T data) {
        Result<T> result = new Result<>(true);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> buildError(String errMsg) {
        Result<T> result = new Result<>(false);
//        result.setErrorCode(SystemErrorCodeEnum.BIZ_ERROR.getErrorCode());
        result.setErrorMsg(errMsg);
        return result;
    }

}
