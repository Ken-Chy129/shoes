package cn.ken.shoes.common;

import lombok.Getter;

@Getter
public enum FileTypeEnum {

    FLAWS("瑕疵.txt"),
    THREE_FIVE("得物35.txt"),
    NOT_COMPARE("不压价.txt"),
    QR_LABEL("订单label.txt"),
    ;

    private final String name;

    FileTypeEnum(String name) {
        this.name = name;
    }
}
