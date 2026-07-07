package com.lusuoria.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Excel 导入用的异步线程池。
 *
 * 单独配一个小的、有界的线程池（不用 Spring 默认的 SimpleAsyncTaskExecutor，那个是
 * 来一个任务开一个线程、没有上限），是因为服务器是 Render 免费版、内存只有 512MB，
 * 得防止"同时好几个人一起导入"把内存和数据库连接池打爆。核心/最大线程数都设得很小，
 * 排队数也做了限制，正常使用（导入本来就不是高频操作）完全够用。
 */
@Configuration
public class AsyncConfig {

    @Bean("importTaskExecutor")
    public Executor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("excel-import-");
        executor.initialize();
        return executor;
    }
}
