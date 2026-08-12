package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findByIsDeletedFalseOrderByNameAsc();

    Optional<Employee> findByIdAndIsDeletedFalse(Long id);

    List<Employee> findByRoleAndIsDeletedFalse(String role);

    /** 不限 isDeleted 精确匹配，供新建员工时复活同名邮箱的已软删除记录用（email 数据库层面
     * 有唯一约束，不认软删除，见 EmployeeController.save()） */
    Optional<Employee> findByEmail(String email);
}