package net.java21.blog.backend.admin.setting;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.admin.setting.dto.SettingResponse;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.setting.SettingKey;
import net.java21.blog.backend.setting.domain.SystemSetting;
import net.java21.blog.backend.setting.repository.SystemSettingRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 운영 설정(003 research P3, contracts/api.md "관리자: 운영 설정"). 키 목록은 {@link SettingKey}가 정하고, 모르는 키는 404
 * {@code SETTING_NOT_FOUND}. 저장·기본값으로는 작업 기록({@code SETTING_CHANGE}, {@code target_key} = 키)에 전후 값을 남기고
 * {@link SystemSettingsService}가 커밋 뒤 포털 캐시를 비운다.
 */
@Service
public class AdminSettingService {

    private final SystemSettingsService settings;
    private final SystemSettingRepository repository;
    private final PortalProperties properties;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;

    public AdminSettingService(SystemSettingsService settings, SystemSettingRepository repository,
            PortalProperties properties, UserRepository userRepository, AdminAuditService auditService) {
        this.settings = settings;
        this.repository = repository;
        this.properties = properties;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** 알려진 키 전체(또는 {@code prefix}로 시작하는 키), 키 이름순. 행은 쿼리 1회로 읽는다. */
    @Transactional(readOnly = true)
    public List<SettingResponse> list(String prefix) {
        Map<String, SystemSetting> rows = rows();
        return Arrays.stream(SettingKey.values())
                .filter(key -> prefix == null || key.key().startsWith(prefix))
                .sorted(Comparator.comparing(SettingKey::key))
                .map(key -> response(key, rows.get(key.key())))
                .toList();
    }

    @Transactional
    public SettingResponse set(long adminId, String keyName, Object value, String requestIp) {
        SettingKey key = SettingKey.require(keyName);
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("value", "REQUIRED")));
        }
        Object before = currentValue(key);
        Object saved = settings.set(key, value, userRepository.getReferenceById(adminId));
        auditService.recordKey(adminId, AuditActions.SETTING_CHANGE, AuditActions.TARGET_SETTING, key.key(),
                value(before), value(saved), requestIp);
        return response(key, rows().get(key.key()));
    }

    /** 기본값으로(행 삭제). 행이 없어도 200. */
    @Transactional
    public SettingResponse reset(long adminId, String keyName, String requestIp) {
        SettingKey key = SettingKey.require(keyName);
        Object before = currentValue(key);
        settings.reset(key);
        auditService.recordKey(adminId, AuditActions.SETTING_CHANGE, AuditActions.TARGET_SETTING, key.key(),
                value(before), value(null), requestIp);
        return response(key, null);
    }

    /** 바꾸기 전 저장값(행이 없으면 null = 기본값). */
    private Object currentValue(SettingKey key) {
        return settings.isOverridden(key) ? settings.value(key) : null;
    }

    private Map<String, SystemSetting> rows() {
        return repository.findAllWithUpdatedBy().stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey, Function.identity()));
    }

    private SettingResponse response(SettingKey key, SystemSetting row) {
        Object defaultValue = key.defaultValue(properties);
        if (row == null) {
            return new SettingResponse(key.key(), defaultValue, defaultValue, false, null, null);
        }
        AdminRef updatedBy = row.getUpdatedBy() == null ? null
                : new AdminRef(row.getUpdatedBy().getId(), row.getUpdatedBy().getNickname());
        return new SettingResponse(key.key(), settings.value(key), defaultValue, true, updatedBy, row.getUpdatedAt());
    }

    /** {@code {"value": v}}(v가 null이면 기본값으로 되돌린 것). */
    private static Map<String, Object> value(Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put("value", value);
        return map;
    }
}
