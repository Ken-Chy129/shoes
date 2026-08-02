package cn.ken.shoes.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSearchOperationTest {

    @Test
    void parsesSupportedOperationCodes() {
        assertThat(ModelSearchOperation.fromCode("fetch_price")).isEqualTo(ModelSearchOperation.FETCH_PRICE);
        assertThat(ModelSearchOperation.fromCode("CREATE_LISTING")).isEqualTo(ModelSearchOperation.CREATE_LISTING);
        assertThat(ModelSearchOperation.fromCode("unknown")).isNull();
    }
}
