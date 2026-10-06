package net.java21.blog.backend.admin.audit;

import java.util.Map;

import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 작업 기록 남기기(T160, 006 FR-106). 기록은 관리 작업과 같은 트랜잭션에서 저장해, 작업이 되돌려지면 기록도 남지 않는다.
 * 변경 전후 값에는 바뀐 필드만, 개인정보 평문 없이 넣는다(이메일 등은 넣지 않는다).
 */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;
    private final UserRepository userRepository;

    public AdminAuditService(AdminAuditLogRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /** 숫자 ID 대상의 변경을 남긴다(사유 없음). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long adminId, String action, String targetType, Long targetId, Map<String, Object> before,
            Map<String, Object> after, String requestIp) {
        repository.save(new AdminAuditLog(userRepository.getReferenceById(adminId), action, targetType, targetId,
                null, before, after, null, requestIp));
    }
}
