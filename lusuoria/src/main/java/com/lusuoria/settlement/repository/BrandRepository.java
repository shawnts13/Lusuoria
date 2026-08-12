package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandRepository extends JpaRepository<Brand, Long> {

    List<Brand> findByIsDeletedFalseOrderByNameAsc();

    Optional<Brand> findByIdAndIsDeletedFalse(Long id);

    boolean existsByNameAndIsDeletedFalse(String name);

    /** 不限 isDeleted 精确匹配，供新建品牌方时复活同名的已软删除记录用（name 数据库层面有
     * 唯一约束，不认软删除，见 BrandController.save()） */
    Optional<Brand> findByName(String name);
}