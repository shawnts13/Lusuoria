package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ProgressReminderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgressReminderDetailRepository extends JpaRepository<ProgressReminderDetail, Long> {

    /** 详情列表：按离最迟结款日的接近程度排序，越近（或超期越久）的排在越前面 */
    List<ProgressReminderDetail> findByReminderIdOrderByDeadlineDateAsc(Long reminderId);

    /**
     * 2026-07 新增：手动"分类重算"用，先删掉指定几个 ProgressReminder id 下的明细行。
     *
     * 2026-08 修复：原来是 Spring Data 派生的 deleteByReminderIdIn（先查出每一行实体，再逐条
     * entityManager.remove()），如果两次重算并发触发（比如手动点了"XX后更新提示内容"，第一次
     * 还没跑完就又点了一次，或者跟每天3点的主批次撞车），后一个事务删到的行可能已经被前一个
     * 事务删掉了，Hibernate 逐条删除时会因为"预期影响1行、实际0行"抛
     * ObjectOptimisticLockingFailureException/StaleStateException，整个请求失败（表现为前端
     * "网络连接失败"，因为耗时也跟着变长）。改成 @Modifying 批量 JPQL DELETE，一条 SQL 直接按
     * WHERE IN 删，不经过持久化上下文逐行核对，命中0行也不报错，天然幂等，两次并发重算不会
     * 再互相炸。
     */
    @Modifying
    @Query("DELETE FROM ProgressReminderDetail d WHERE d.reminderId IN :reminderIds")
    void deleteByReminderIdIn(@org.springframework.data.repository.query.Param("reminderIds") List<Long> reminderIds);
}
