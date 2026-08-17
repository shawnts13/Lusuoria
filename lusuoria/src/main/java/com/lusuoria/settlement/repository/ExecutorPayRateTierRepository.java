package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ExecutorPayRateTierRepository extends JpaRepository<ExecutorPayRateTier, Long> {

    /** 全表未删除的档位（2026-08-17 新增，供 ExecutorPayRateTierCache 启动/刷新时一次性加载用） */
    List<ExecutorPayRateTier> findByIsDeletedFalse();

    /** 某个负责人名下配置的全部档位（覆盖他手下所有执行人员/所有视频类型，按最低条数升序） */
    List<ExecutorPayRateTier> findByManagerIdAndIsDeletedFalseOrderByMinCountAsc(Long managerId);

    /** 批量取多个负责人名下配置的全部档位，供列表页一次性判断"是否已配置费率"用，避免逐行查库 */
    List<ExecutorPayRateTier> findByManagerIdInAndIsDeletedFalse(Collection<Long> managerIds);

    /** 某个 (负责人,执行人员,视频类型) 的档位列表，按最低条数升序 */
    List<ExecutorPayRateTier> findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
            Long managerId, Long executorId, VideoType videoType);

    /** 真删（非软删）：保存某个负责人对某个执行人员的费率配置时先整体清空旧档位再重新插入，档位本身没有独立追溯价值 */
    void deleteByManagerIdAndExecutorId(Long managerId, Long executorId);
}
