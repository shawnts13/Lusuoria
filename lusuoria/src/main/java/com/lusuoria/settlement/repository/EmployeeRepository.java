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
}