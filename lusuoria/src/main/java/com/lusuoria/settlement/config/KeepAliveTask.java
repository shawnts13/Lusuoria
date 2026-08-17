package com.lusuoria.settlement.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 定时心跳任务，防止 Render 免费版因 15 分钟无请求而休眠
 * 每 10 分钟执行一次轻量级数据库 ping，保持服务活跃
 */
@Component
public class KeepAliveTask {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveTask.class);

    @Autowired private DataSource dataSource;

    /** 借一个连接验证一下有效性就还回去，不查表，纯粹是为了有"数据库活动"防止 Render 休眠 */
    @Scheduled(fixedDelay = 10 * 60 * 1000)  // 每10分钟
    public void ping() {
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(3);  // 3秒超时，只是验证连接有效，不查任何表
            log.debug("Keep-alive ping OK");
        } catch (Exception e) {
            log.warn("Keep-alive ping failed: {}", e.getMessage());
        }
    }
}
