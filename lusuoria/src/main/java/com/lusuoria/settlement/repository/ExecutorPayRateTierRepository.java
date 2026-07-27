package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ExecutorPayRateTierRepository extends JpaRepository<ExecutorPayRateTier, Long> {

    /** 某个负责人名下配置的全部档位（覆盖他手下所有执行人员/所有视频类型，按最低条数升序） */
    List<ExecutorPayRateTier> findByManagerIdAndIsDeletedFalseOrderByMinCountAsc(Long managerId);

    /** 批量取多个负责人名下配置的全部档位，供列表页一次性判断"是否已配置费率"用，避免逐行查库 */
    List<ExecutorPayRateTier> findByManagerIdInAndIsDeletedFalse(Collection<Long> managerIds);

    /** 某个 (负责人,执行人员,视频类型) 的档位列表，按最低条数升序 */
    List<ExecutorPayRateTier> findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
            Long managerId, Long executorId, VideoType videoType);

    void deleteByManagerIdAndExecutorId(Long managerId, Long executorId);
}
