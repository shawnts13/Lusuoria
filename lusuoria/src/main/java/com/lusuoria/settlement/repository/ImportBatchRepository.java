package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ImportBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    Page<ImportBatch> findByModuleAndIsDeletedFalseOrderByCreatedAtDesc(String module, Pageable pageable);

    Optional<ImportBatch> findByIdAndIsDeletedFalse(Long id);
}
