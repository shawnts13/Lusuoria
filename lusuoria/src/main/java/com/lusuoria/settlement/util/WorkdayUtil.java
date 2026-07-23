package com.lusuoria.settlement.util;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 工作日计算：只有周六算休息日，周日算工作日，不查中国法定节假日/调休（已跟用户确认过，
 * 简化处理）。供进度滞留/Invoice逾期提醒批次统一使用。
 */
public class WorkdayUtil {

    private WorkdayUtil() {
    }

    /**
     * 统计 [start, today.minusDays(1)] 区间内的工作日天数（周一至周五 + 周日，只排除周六；
     * today 当天不算，因为批次是当天凌晨3点跑的，那时"今天"这个工作日还没真正开始——进度被
     * 设置的当天算第1个工作日，例：7月20日周一设置状态，20/21/22号=第3个工作日，23号凌晨
     * 跑批时刚好命中）。
     * start 为空，或晚于等于 today 时返回 0。
     */
    public static int countWeekdaysInclusive(LocalDate start, LocalDate today) {
        if (start == null) return 0;
        LocalDate end = today.minusDays(1);
        if (start.isAfter(end)) return 0;
        int count = 0;
        LocalDate d = start;
        while (!d.isAfter(end)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY) count++;
            d = d.plusDays(1);
        }
        return count;
    }
}
