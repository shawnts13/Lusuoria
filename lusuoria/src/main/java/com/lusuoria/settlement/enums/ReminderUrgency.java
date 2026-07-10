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

    /** 根据剩余天数（可为负，负数/0表示已超期）判定档位；超过7天不需要提醒，返回 null */
    public static ReminderUrgency fromDaysRemaining(long daysRemaining) {
        if (daysRemaining <= 0) return OVERDUE;
        if (daysRemaining <= 3) return NEAR;
        if (daysRemaining <= 7) return UPCOMING;
        return null;
    }
}
