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

    /**
     * 【Spring 知识点】@Bean("importTaskExecutor") 里括号中的字符串是这个 Bean 在容器里注册的
     * 名字。别的地方要用这个线程池时，写 @Async("importTaskExecutor")（按名字指定）——如果不写
     * 名字、直接 @Async，Spring 会去找容器里"类型是 Executor 且唯一"的那个 Bean，项目里如果以后
     * 加了第二个线程池配置，那种写法就会因为"存在多个候选 Bean、不知道用哪个"而报错，所以这里
     * 显式给了名字，调用方也显式指定，双方都不依赖"容器里只有一个 Executor"这个隐含前提。
     *
     * corePoolSize/maxPoolSize/queueCapacity 三者的关系（ThreadPoolTaskExecutor 底层就是
     * java.util.concurrent.ThreadPoolExecutor）：新任务进来时，线程数没到 corePoolSize 就开新
     * 线程处理；到了 corePoolSize 之后，新任务先进队列排队（最多排 queueCapacity 个）；队列也满了，
     * 才会继续开线程直到 maxPoolSize；如果连 maxPoolSize 都到了队列还满，默认策略是直接拒绝任务
     * 抛异常（这里没配拒绝策略，用的是 Spring 默认的 AbortPolicy）。当前配置（1核心/2最大/20排队）
     * 意味着：平时只用 1 个线程慢慢处理导入任务，最多同时有 2 个导入在真正跑，超过这个数量的请求
     * 会排队等，等到 22 个还没处理完才会开始报错——这在免费版服务器内存有限的前提下是刻意收紧的。
     */
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
