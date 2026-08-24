package cn.ken.shoes.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KickScrewClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withSystemProperties(
                    "kc.apiKey=test-api-key",
                    "KC_STOREFRONT_TOKEN=test-storefront-token"
            )
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsStorefrontTokenFromDeploymentEnvironmentVariable() {
        contextRunner.run(context -> {
            KickScrewClient client = context.getBean(KickScrewClient.class);

            assertThat(ReflectionTestUtils.getField(client, "storefrontToken"))
                    .isEqualTo("test-storefront-token");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        KickScrewClient kickScrewClient() {
            return new KickScrewClient();
        }
    }
}
