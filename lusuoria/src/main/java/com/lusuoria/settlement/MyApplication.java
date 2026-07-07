package com.lusuoria.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@EnableAsync
public class MyApplication {
    public static void main(String[] args) {
        // 这个系统是给中国用户用的，不管服务器实际部署在哪个地区，
        // 所有日期/时间的解析、存储、生成都统一按北京时间处理。
        // 必须在 SpringApplication.run() 之前设置，这样 Hibernate/JDBC/POI（Excel解析）
        // 这些在启动阶段就会读取 JVM 默认时区的组件，才能拿到正确的值。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(MyApplication.class, args);
    }
}