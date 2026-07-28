package com.lusuoria.settlement.enums;

/**
 * "超期提醒"严重程度分档（2026-07 新增，供 PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL/
 * REQUIREMENT_INVOICE_OVERDUE 这3类新提醒使用）。
 *
 * 跟 {@link ReminderUrgency} 语义方向相反——那个是"离最迟期限还剩几天"（倒数），这个是
 * "已经超出阈值几天了"（正数往上累加），所以不复用同一个枚举，颜色约定也不同：
 * 1-3天=黄，4-7天=橙，超出7天(8+)=红（边界不重叠，避免同一天数落两档）。
 */
public enum OverdueUrgency {
    MILD("1-3天", "gold"),
    MODERATE("3-7天", "orange"),
    SEVERE("超出7天", "red");

    private final String label;
    private final String color;

    OverdueUrgency(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    /** 超出天数 <= 0 表示还没到阈值，不生成提醒，返回 null。2026-07-28 起边界改成可配置
     * （见 {@link #fromOverdueDays(int, int, int)}），这个无参数版本固定用 3/7 的老默认值，
     * 只在没有具体提醒类型上下文时兜底调用。 */
    public static OverdueUrgency fromOverdueDays(int overdueDays) {
        return fromOverdueDays(overdueDays, 3, 7);
    }

    /**
     * 2026-07-28 新增：边界可配置版本，供 ProgressReminderService 按提醒类型从
     * {@link com.lusuoria.settlement.config.ReminderThresholdCache} 读到的 mildMaxDays/
     * moderateMaxDays 传进来——不同提醒类型的"轻度"/"中度"边界现在可以各自独立配置。
     */
    public static OverdueUrgency fromOverdueDays(int overdueDays, int mildMaxDays, int moderateMaxDays) {
        if (overdueDays <= 0) return null;
        if (overdueDays <= mildMaxDays) return MILD;
        if (overdueDays <= moderateMaxDays) return MODERATE;
        return SEVERE;
    }
}
