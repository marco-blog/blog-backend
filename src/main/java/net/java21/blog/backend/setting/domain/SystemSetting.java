package net.java21.blog.backend.setting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 운영 설정값(system_settings, 003 research P3). 키는 {@code SettingKey}가 정하고 값은 키마다 정해진 형식의 JSON(객체·문자열·숫자)이다.
 * 행이 없으면 같은 의미의 backend 프로퍼티가 기본값이다.
 */
@Entity
@Table(name = "system_settings")
public class SystemSetting extends BaseTimeEntity {

    @Id
    @Column(name = "setting_key", length = 100)
    private String settingKey;

    /** JSON 값. 객체는 {@code Map}, 문자열은 {@code String}, 숫자는 {@code Number}로 읽힌다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_json", nullable = false)
    private Object value;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    protected SystemSetting() {
    }

    public SystemSetting(String settingKey, Object value, User updatedBy) {
        this.settingKey = settingKey;
        this.value = value;
        this.updatedBy = updatedBy;
    }

    public void change(Object value, User updatedBy) {
        this.value = value;
        this.updatedBy = updatedBy;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public Object getValue() {
        return value;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }
}
