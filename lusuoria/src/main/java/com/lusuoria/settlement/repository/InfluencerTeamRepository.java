package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerTeamRepository extends JpaRepository<InfluencerTeam, Long> {

    List<InfluencerTeam> findByIsDeletedFalseOrderByNameAsc();

    Optional<InfluencerTeam> findByNameAndIsDeletedFalse(String name);

    /** 按名称查询，不论是否软删除（用于复活软删除的团队） */
    Optional<InfluencerTeam> findByName(String name);

    boolean existsByNameAndIsDeletedFalse(String name);
}
