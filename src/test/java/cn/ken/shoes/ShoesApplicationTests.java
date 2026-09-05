package cn.ken.shoes;

import cn.ken.shoes.listener.ApplicationStartListener;
import cn.ken.shoes.listener.ConfigLoadListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ShoesApplicationTests {

    @MockitoBean
    private ApplicationStartListener applicationStartListener;

    @MockitoBean
    private ConfigLoadListener configLoadListener;

    @Test
    void contextLoads() {
    }

}
