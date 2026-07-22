package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerRequirementItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfluencerRequirementItemRepository extends JpaRepository<InfluencerRequirementItem, Long> {

    List<InfluencerRequirementItem> findByRequirementIdOrderByIdAsc(Long requirementId);

    /** 需求列表页/批量查询用：一次性取出这一批需求的全部条目，避免逐条查库 */
    List<InfluencerRequirementItem> findByRequirementIdInOrderByIdAsc(List<Long> requirementIds);
}
