package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DomainRepository extends JpaRepository<Domain, Long> {

    List<Domain> findByIsDeletedFalseOrderByNameAsc();

    Optional<Domain> findByNameAndIsDeletedFalse(String name);

    /** 按名称查询，不论是否软删除（用于复活软删除的领域，避免唯一约束冲突） */
    Optional<Domain> findByName(String name);

    boolean existsByNameAndIsDeletedFalse(String name);
}
