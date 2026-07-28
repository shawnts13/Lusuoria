package com.lusuoria.settlement.config;

import com.lusuoria.settlement.entity.ReminderThresholdConfig;
import com.lusuoria.settlement.enums.ReminderCategory;
import com.lusuoria.settlement.repository.ReminderThresholdConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.lusuoria.settlement.enums.ReminderCategory.*;

/**
 * 进度提醒阈值配置缓存（2026-07-28 新增）：{@link ProgressReminderService} 里所有原来写死的
 * 工作日阈值/提醒分档天数边界，统一从这里读，不再是代码里的 magic number。启动时如果表是空的
 * （第一次上线这个功能），按 {@link #DEFAULTS} 这份清单把当前代码里原本写死的值整体写进数据库，
 * 管理层在"进度提醒阈值维护"页面看到的就是改动前完全一样的行为，改了值之后调用 refresh()
 * 立刻生效（下一次跑批/手动触发重新计算时就会用新值）。
 *
 * 跟其它 *Cache 的读法不同：这里不是"查一个实体"，而是 getInt(category, paramKey, 兜底默认值)
 * 拿一个整数——兜底默认值只在缓存里确实找不到这一项时才会用到（正常不会发生，因为
 * init() 已经把 DEFAULTS 补全），是防御性的最后一道保险，不是真正依赖的配置来源。
 */
@Component
public class ReminderThresholdCache {

    @Autowired private ReminderThresholdConfigRepository repo;

    /** {category, paramKey, paramLabel, defaultValue, unit, sortOrder} —— 跟改造前
     *  ProgressReminderService 里写死的值一一对应，是这个功能上线时的"迁移前后行为不变"基准 */
    private static final Object[][] DEFAULTS = {
            {PM_EXECUTOR_PROGRESS_STALL, "STALL_THRESHOLD_ORDERED", "「红人已下单」滞留阈值", 5, "工作日", 1},
            {PM_EXECUTOR_PROGRESS_STALL, "STALL_THRESHOLD_MID",
                    "其余进度状态滞留阈值（待客户简报/合同待发送/拍摄指南待发送/待初稿/待修改/待发布）", 3, "工作日", 2},
            {PM_EXECUTOR_PROGRESS_STALL, "TIER_MILD_MAX_DAYS", "轻度提醒边界（超出阈值多少天内算轻度/黄色）", 3, "天", 3},
            {PM_EXECUTOR_PROGRESS_STALL, "TIER_MODERATE_MAX_DAYS",
                    "中度提醒边界（超出阈值多少天内算中度/橙色，超过则为重度/红色）", 7, "天", 4},

            {FINANCE_PROGRESS_STALL, "STALL_THRESHOLD",
                    "「已发布(未结算)/已加入客户未结算列表」流转到「客户已结算」的滞留阈值", 14, "工作日", 1},
            {FINANCE_PROGRESS_STALL, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离阈值还剩多少天内算临近/橙色）", 3, "天", 2},
            {FINANCE_PROGRESS_STALL, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离阈值还剩多少天内开始提醒/绿色）", 7, "天", 3},

            {REQUIREMENT_INVOICE_OVERDUE, "OVERDUE_THRESHOLD", "Invoice上传逾期阈值", 5, "工作日", 1},
            {REQUIREMENT_INVOICE_OVERDUE, "TIER_MILD_MAX_DAYS", "轻度提醒边界（超出阈值多少天内算轻度/黄色）", 3, "天", 2},
            {REQUIREMENT_INVOICE_OVERDUE, "TIER_MODERATE_MAX_DAYS",
                    "中度提醒边界（超过则为重度/红色）", 7, "天", 3},

            {REQUIREMENT_CONTRACT_OVERDUE, "OVERDUE_THRESHOLD", "合同上传逾期阈值", 14, "工作日", 1},
            {REQUIREMENT_CONTRACT_OVERDUE, "TIER_MILD_MAX_DAYS", "轻度提醒边界（超出阈值多少天内算轻度/黄色）", 3, "天", 2},
            {REQUIREMENT_CONTRACT_OVERDUE, "TIER_MODERATE_MAX_DAYS",
                    "中度提醒边界（超过则为重度/红色）", 7, "天", 3},

            {CONTRACT_EXPIRING_SOON, "EXPIRY_WINDOW_DAYS", "合同到期提醒窗口（到期前多少天开始提醒）", 30, "天", 1},
            {CONTRACT_EXPIRING_SOON, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离到期还剩多少天内算临近/橙色）", 14, "天", 2},

            {COLLAB_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离最迟结款日还剩多少天内算临近/橙色）", 3, "天", 1},
            {COLLAB_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离最迟结款日还剩多少天内开始提醒/绿色）", 7, "天", 2},

            {BRAND_MONTH_END_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离结款日还剩多少天内算临近/橙色）", 3, "天", 1},
            {BRAND_MONTH_END_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离结款日还剩多少天内开始提醒/绿色）", 7, "天", 2},

            {INFLUENCER_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离预计付款日还剩多少天内算临近/橙色）", 3, "天", 1},
            {INFLUENCER_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离预计付款日还剩多少天内开始提醒/绿色）", 7, "天", 2},
    };

    private volatile Map<String, Integer> values = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        seedDefaultsIfEmpty();
        refresh();
    }

    private synchronized void seedDefaultsIfEmpty() {
        if (repo.count() > 0) return;
        List<ReminderThresholdConfig> toInsert = new ArrayList<>();
        for (Object[] row : DEFAULTS) {
            ReminderThresholdConfig c = new ReminderThresholdConfig();
            c.setIsDeleted(false);
            c.setCategory((ReminderCategory) row[0]);
            c.setParamKey((String) row[1]);
            c.setParamLabel((String) row[2]);
            c.setParamValue((Integer) row[3]);
            c.setUnit((String) row[4]);
            c.setSortOrder((Integer) row[5]);
            toInsert.add(c);
        }
        repo.saveAll(toInsert);
    }

    @Scheduled(fixedDelay = 4 * 60 * 60 * 1000)
    public synchronized void refresh() {
        Map<String, Integer> m = new ConcurrentHashMap<>();
        for (ReminderThresholdConfig c : repo.findAll()) {
            m.put(key(c.getCategory(), c.getParamKey()), c.getParamValue());
        }
        values = m;
    }

    /** 读一个阈值参数；正常情况下 init() 已经把 DEFAULTS 整表补全，fallback 只在异常情况下兜底 */
    public int getInt(ReminderCategory category, String paramKey, int fallback) {
        Integer v = values.get(key(category, paramKey));
        return v != null ? v : fallback;
    }

    private String key(ReminderCategory category, String paramKey) {
        return category.name() + "::" + paramKey;
    }
}
