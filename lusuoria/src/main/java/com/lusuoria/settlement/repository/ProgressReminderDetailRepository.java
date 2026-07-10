package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ProgressReminderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgressReminderDetailRepository extends JpaRepository<ProgressReminderDetail, Long> {

    /** 详情列表：按离最迟结款日的接近程度排序，越近（或超期越久）的排在越前面 */
    List<ProgressReminderDetail> findByReminderIdOrderByDeadlineDateAsc(Long reminderId);
}
