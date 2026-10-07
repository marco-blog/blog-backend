package net.java21.blog.backend.setting.repository;

import java.util.List;

import net.java21.blog.backend.setting.domain.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {

    /** 모든 행과 마지막으로 바꾼 관리자(관리자 설정 목록, 쿼리 1회). */
    @Query("select s from SystemSetting s left join fetch s.updatedBy")
    List<SystemSetting> findAllWithUpdatedBy();
}
