package net.java21.blog.backend.setting.repository;

import net.java21.blog.backend.setting.domain.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
