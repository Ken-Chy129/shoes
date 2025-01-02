package cn.ken.shoes.config;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;

@Slf4j
@Component
public class DruidConfig {

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    @Bean
    @Primary
    public DataSource druidDataSource() {
        DruidDataSource datasource = new DruidDataSource();
        datasource.setUrl(this.dbUrl);
        datasource.setUsername(username);
        datasource.setPassword(password);
        datasource.setDriverClassName(driverClassName);
        datasource.setInitialSize(10);
        datasource.setMinIdle(10);
        datasource.setMaxActive(200);
        datasource.setMaxWait(60000);
        datasource.setTimeBetweenEvictionRunsMillis(45000);
        datasource.setValidationQuery("SELECT 1");
        datasource.setTestWhileIdle(true);
        datasource.setTestOnBorrow(true);
        datasource.setTestOnReturn(false);
        datasource.setRemoveAbandonedTimeout(3600);
        try {
            datasource.setFilters("stat");
            datasource.init();
        } catch (SQLException e) {
            log.error("druid configuration initialization filter", e);
        }
        return datasource;
    }


    @Bean
    @DependsOn("druidDataSource")
    public SqlSessionFactory sqlSessionFactory(@Qualifier("druidDataSource") DataSource dataSource) throws Exception {
        log.info("Initializing SqlSessionFactory with Druid DataSource.");
        SqlSessionFactoryBean sqlSessionFactoryBean = new SqlSessionFactoryBean();
        sqlSessionFactoryBean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        sqlSessionFactoryBean.setDataSource(dataSource);
        return sqlSessionFactoryBean.getObject();
    }
}
