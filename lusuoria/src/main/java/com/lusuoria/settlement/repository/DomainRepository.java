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

    boolean existsByNameAndIsDeletedFalse(String name);
}
