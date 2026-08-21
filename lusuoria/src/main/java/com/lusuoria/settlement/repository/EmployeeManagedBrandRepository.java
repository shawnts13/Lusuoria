package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.EmployeeManagedBrand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeManagedBrandRepository extends JpaRepository<EmployeeManagedBrand, Long> {

    /** 全表未删除的关联，供 EmployeeManagedBrandCache 启动/刷新时一次性加载用 */
    List<EmployeeManagedBrand> findByIsDeletedFalse();

    /** 某个项目管理员当前负责的品牌方关联（含已软删除的，供保存时"复活软删记录"用，见
     *  EmployeeController.save() 里"getOrCreate 式"整理逻辑，避免撞唯一索引） */
    List<EmployeeManagedBrand> findByEmployeeId(Long employeeId);
}
