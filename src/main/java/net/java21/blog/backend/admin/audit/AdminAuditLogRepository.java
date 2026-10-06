package net.java21.blog.backend.admin.audit;

import java.util.List;

import org.springframework.data.repository.Repository;

/** 작업 기록 저장소(T160). 기록은 쓰기와 읽기만 한다(수정·삭제 메서드를 두지 않는다, 006 FR-106). */
public interface AdminAuditLogRepository extends Repository<AdminAuditLog, Long> {

    AdminAuditLog save(AdminAuditLog log);

    /** 대상의 기록(최근 것 먼저). 006 작업 기록 화면 전까지는 확인용. */
    List<AdminAuditLog> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId);
}
