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
 * 工作日阈值/提醒分档天数边界，统一从这里读，不再是代码里的 magic number。启动时按
 * {@link #DEFAULTS} 这份清单逐行补齐数据库里缺的行（见 seedMissingDefaults，不是"表是空的才种"，
 * 以后往 DEFAULTS 里新增参数也能自动补上，不会因为表已经有数据就整体跳过），管理层在"进度提醒
 * 阈值维护"页面看到的就是改动前完全一样的行为，改了值之后调用 refresh()
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
            {FINANCE_PROGRESS_STALL, "TIER_OVERDUE_MAX_DAYS", "0天或已超期边界（红色，到了/超过阈值多少天内算这一档）", 0, "天", 2},
            {FINANCE_PROGRESS_STALL, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离阈值还剩多少天内算临近/橙色）", 3, "天", 3},
            {FINANCE_PROGRESS_STALL, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离阈值还剩多少天内开始提醒/绿色）", 7, "天", 4},

            {REQUIREMENT_INVOICE_OVERDUE, "OVERDUE_THRESHOLD", "Invoice上传逾期阈值", 5, "工作日", 1},
            {REQUIREMENT_INVOICE_OVERDUE, "TIER_MILD_MAX_DAYS", "轻度提醒边界（超出阈值多少天内算轻度/黄色）", 3, "天", 2},
            {REQUIREMENT_INVOICE_OVERDUE, "TIER_MODERATE_MAX_DAYS",
                    "中度提醒边界（超过则为重度/红色）", 7, "天", 3},

            {REQUIREMENT_CONTRACT_OVERDUE, "OVERDUE_THRESHOLD", "合同上传逾期阈值", 14, "工作日", 1},
            {REQUIREMENT_CONTRACT_OVERDUE, "TIER_MILD_MAX_DAYS", "轻度提醒边界（超出阈值多少天内算轻度/黄色）", 3, "天", 2},
            {REQUIREMENT_CONTRACT_OVERDUE, "TIER_MODERATE_MAX_DAYS",
                    "中度提醒边界（超过则为重度/红色）", 7, "天", 3},

            {CONTRACT_EXPIRING_SOON, "TIER_OVERDUE_MAX_DAYS", "0天或已过期边界（红色，到了/超过到期日多少天内算这一档）", 0, "天", 1},
            {CONTRACT_EXPIRING_SOON, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离到期还剩多少天内算临近/橙色）", 14, "天", 2},
            {CONTRACT_EXPIRING_SOON, "EXPIRY_WINDOW_DAYS",
                    "预告提醒边界（距离到期还剩多少天内算预告/黄色，同时也是到期前多少天开始提醒的总窗口）", 30, "天", 3},

            {COLLAB_PAYMENT_DUE, "TIER_OVERDUE_MAX_DAYS", "0天或已超期边界（红色，到了/超过最迟结款日多少天内算这一档）", 0, "天", 1},
            {COLLAB_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离最迟结款日还剩多少天内算临近/橙色）", 3, "天", 2},
            {COLLAB_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离最迟结款日还剩多少天内开始提醒/绿色）", 7, "天", 3},

            {BRAND_MONTH_END_PAYMENT_DUE, "TIER_OVERDUE_MAX_DAYS", "0天或已超期边界（红色，到了/超过结款日多少天内算这一档）", 0, "天", 1},
            {BRAND_MONTH_END_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离结款日还剩多少天内算临近/橙色）", 3, "天", 2},
            {BRAND_MONTH_END_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离结款日还剩多少天内开始提醒/绿色）", 7, "天", 3},

            {INFLUENCER_PAYMENT_DUE, "TIER_OVERDUE_MAX_DAYS", "0天或已超期边界（红色，到了/超过预计付款日多少天内算这一档）", 0, "天", 1},
            {INFLUENCER_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", "临近提醒边界（距离预计付款日还剩多少天内算临近/橙色）", 3, "天", 2},
            {INFLUENCER_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", "预告提醒边界（距离预计付款日还剩多少天内开始提醒/绿色）", 7, "天", 3},
    };

    private volatile Map<String, Integer> values = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        seedMissingDefaults();
        refresh();
    }

    /**
     * 按 (category, paramKey) 逐行补齐，不是"整张表是空的才种"——第一次上线这个功能时表是空的，
     * 会整表插入；以后 DEFAULTS 清单里新增参数（比如 2026-07-29 补的 TIER_OVERDUE_MAX_DAYS）时，
     * 表已经不是空的了，只逐行补上缺的那几条，不会因为表已经有数据就整体跳过，导致新参数永远
     * 插不进去。
     *
     * 已存在的行：paramValue（管理层可能已经手动改过）永远不覆盖；但 paramLabel/unit/sortOrder
     * 这几个纯展示用的字段每次启动都会跟 DEFAULTS 同步一遍——这几个后续可能会继续打磨措辞/
     * 调整分组内的展示顺序（2026-07-29 就把 CONTRACT_EXPIRING_SOON 的 EXPIRY_WINDOW_DAYS
     * 从第1位挪到第3位、顺带把标签写清楚是"预告/黄色"边界），不应该因为这一行早就插过就永远
     * 停留在旧文案/旧顺序。
     */
    private synchronized void seedMissingDefaults() {
        List<ReminderThresholdConfig> toSave = new ArrayList<>();
        for (Object[] row : DEFAULTS) {
            ReminderCategory category = (ReminderCategory) row[0];
            String paramKey = (String) row[1];
            String label = (String) row[2];
            Integer defaultValue = (Integer) row[3];
            String unit = (String) row[4];
            Integer sortOrder = (Integer) row[5];
            ReminderThresholdConfig c = repo.findByCategoryAndParamKey(category, paramKey).orElse(null);
            if (c == null) {
                c = new ReminderThresholdConfig();
                c.setIsDeleted(false);
                c.setCategory(category);
                c.setParamKey(paramKey);
                c.setParamValue(defaultValue);
            }
            c.setParamLabel(label);
            c.setUnit(unit);
            c.setSortOrder(sortOrder);
            toSave.add(c);
        }
        repo.saveAll(toSave);
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
