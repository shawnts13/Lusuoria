package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.TeamContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface TeamContractRepository extends JpaRepository<TeamContract, Long> {

    /** 某个团队的合同列表（有效期起始日期倒序），供"品牌方/红人团队管理"里"查看合同"展示 */
    List<TeamContract> findByTeamIdOrderByStartDateDesc(Long teamId);

    /** 该品牌方下"没有团队"的合同列表（teamId 为空的场景，见 TeamContract 类注释） */
    List<TeamContract> findByBrandIdAndTeamIdIsNullOrderByStartDateDesc(Long brandId);

    /** 批量按团队 id 取合同，供"红人需求管理"批量交叉核对、合同到期提醒批量查询用，避免逐条查库 */
    List<TeamContract> findByTeamIdIn(List<Long> teamIds);

    /** 批量按品牌方 id 取"该品牌方下没有团队"的合同，跟 findByTeamIdIn 是互补的两半 */
    List<TeamContract> findByBrandIdInAndTeamIdIsNull(List<Long> brandIds);

    /**
     * 同一个 (品牌方,团队) 下，跟给定日期区间有重叠的已有合同（新增/编辑时用来拒绝有效期冲突的
     * 记录）。teamId 允许为空（该品牌方下没有团队层的场景），按 null 精确匹配 null。excludeId
     * 编辑时传当前记录自己的 id，排除自身；新增时传 null。
     */
    @Query("SELECT c FROM TeamContract c WHERE c.brandId = :brandId " +
           "AND ((:teamId IS NULL AND c.teamId IS NULL) OR c.teamId = :teamId) " +
           "AND c.startDate <= :endDate AND c.endDate >= :startDate " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    List<TeamContract> findOverlapping(@Param("brandId") Long brandId,
                                        @Param("teamId") Long teamId,
                                        @Param("startDate") Date startDate,
                                        @Param("endDate") Date endDate,
                                        @Param("excludeId") Long excludeId);
}
