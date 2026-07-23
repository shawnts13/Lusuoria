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

    /** 超出天数 <= 0 表示还没到阈值，不生成提醒，返回 null */
    public static OverdueUrgency fromOverdueDays(int overdueDays) {
        if (overdueDays <= 0) return null;
        if (overdueDays <= 3) return MILD;
        if (overdueDays <= 7) return MODERATE;
        return SEVERE;
    }
}
