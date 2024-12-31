package cn.ken.shoes;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("cn.ken.shoes.mapper")
public class ShoesApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShoesApplication.class, args);
    }

}
