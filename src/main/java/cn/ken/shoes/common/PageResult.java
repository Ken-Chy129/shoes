package cn.ken.shoes.common;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> extends Result<T> {

    private Integer pageIndex;

    private Integer pageSize;

    private Long total;

    private Long pageCount;

    private boolean hasMore;

    public PageResult(Boolean success) {
        super(success);
    }

    public static <T> PageResult<T> buildSuccess() {
        PageResult<T> result = new PageResult<>(true);
        result.setTotal(0L);
        result.setHasMore(false);
        return result;
    }

    public static <P extends List<?>> PageResult<P> buildSuccess(P data) {
        PageResult<P> result = new PageResult<>(true);
        if (data == null || data.isEmpty()) {
            result.setHasMore(false);
            result.setTotal(0L);
            return result;
        }
        result.setData(data);
        return result;
    }

    public static <T> PageResult<T> buildError(String errMsg) {
        PageResult<T> result = new PageResult<>(false);
//        result.setErrorCode(SystemErrorCodeEnum.BIZ_ERROR.getErrorCode());
        result.setErrorMsg(errMsg);
        return result;
    }

}