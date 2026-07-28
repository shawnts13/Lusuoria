package com.lusuoria.settlement.enums;

/**
 * 进度提醒紧急程度分档（离最迟结款日的天数）。
 * 三档共用同一套颜色约定：0天或已超期=红，1-3天=橙，3-7天=绿（前端按这个映射上色）。
 * 边界处理：daysRemaining &lt;= 0 归 OVERDUE；1~3 归 NEAR；4~7 归 UPCOMING（避免 3 天同时落两档）；
 * 超过 7 天不生成提醒。
 */
public enum ReminderUrgency {
    OVERDUE("0天或已超期", "red"),
    NEAR("1-3天", "orange"),
    UPCOMING("3-7天", "green");

    private final String label;
    private final String color;

    ReminderUrgency(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }

    /** 根据剩余天数（可为负，负数/0表示已超期）判定档位；超过7天不需要提醒，返回 null。
     * 2026-07-28 起边界改成可配置（见 {@link #fromDaysRemaining(long, int, int)}），这个
     * 无参数版本固定用 3/7 的老默认值，只在没有具体提醒类型上下文时兜底调用。 */
    public static ReminderUrgency fromDaysRemaining(long daysRemaining) {
        return fromDaysRemaining(daysRemaining, 3, 7);
    }

    /**
     * 2026-07-28 新增：边界可配置版本，供 ProgressReminderService 按提醒类型从
     * {@link com.lusuoria.settlement.config.ReminderThresholdCache} 读到的 nearMaxDays/
     * windowMaxDays 传进来——不同提醒类型的"临近"/"预告"边界现在可以各自独立配置，不再是
     * 全部共用同一套写死的 3/7。
     */
    public static ReminderUrgency fromDaysRemaining(long daysRemaining, int nearMaxDays, int windowMaxDays) {
        if (daysRemaining <= 0) return OVERDUE;
        if (daysRemaining <= nearMaxDays) return NEAR;
        if (daysRemaining <= windowMaxDays) return UPCOMING;
        return null;
    }
}
