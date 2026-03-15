package cn.ken.shoes.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {

    private static ProxyConfig instance;

    private String host;
    private Integer port;
    private String username;
    private String password;

    @PostConstruct
    public void init() {
        instance = this;
    }

    public static ProxyConfig getInstance() {
        return instance;
    }
}
