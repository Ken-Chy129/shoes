package cn.ken.shoes.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务类型枚举
 */
@Getter
@AllArgsConstructor
public enum TaskTypeEnum {

    LISTING("listing", "上架"),
    PRICE_DOWN("price_down", "压价"),
    ;

    private final String code;
    private final String desc;

    public static TaskTypeEnum fromCode(String code) {
        for (TaskTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
