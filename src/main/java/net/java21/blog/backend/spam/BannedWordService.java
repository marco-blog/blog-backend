package net.java21.blog.backend.spam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.spam.dto.BannedWordRequest;
import net.java21.blog.backend.admin.spam.dto.BannedWordResponse;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.text.TextNormalizer;
import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.spam.repository.BannedWordQueryRepository;
import net.java21.blog.backend.spam.repository.BannedWordRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 금칙어 관리(005 FR-143, research M11, contracts/api.md "관리자: 스팸 방어").
 * <ul>
 *   <li>단어는 NFKC·앞뒤 공백 제거·소문자로 정규화해 1~50자. 중복 409 {@code BANNED_WORD_EXISTS}.</li>
 *   <li>적용 범위가 NAME이면 처리 방식은 REJECT만(MASK면 400 field {@code action} {@code INVALID}).</li>
 *   <li>바꿀 때마다 작업 기록({@code BANNED_WORD_*})을 남기고, 커밋 뒤 {@link BannedWordMatcher}가 목록을 다시 읽는다.</li>
 * </ul>
 */
@Service
public class BannedWordService {

    private final BannedWordRepository repository;
    private final BannedWordQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;

    public BannedWordService(BannedWordRepository repository, BannedWordQueryRepository queryRepository,
            UserRepository userRepository, AdminAuditService auditService, ApplicationEventPublisher events) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<BannedWordResponse> list(String q, Pageable pageable) {
        return queryRepository.search(TextNormalizer.nfkcLower(q), pageable).map(BannedWordResponse::of);
    }

    @Transactional
    public BannedWordResponse create(long adminId, BannedWordRequest request, String requestIp) {
        List<FieldError> errors = new ArrayList<>();
        String word = TextNormalizer.nfkcLower(request == null ? null : request.word());
        if (word.isEmpty()) {
            errors.add(FieldError.of("word", "REQUIRED"));
        } else if (word.codePointCount(0, word.length()) > BannedWord.WORD_MAX) {
            errors.add(new FieldError("word", "TOO_LONG", Map.of("max", BannedWord.WORD_MAX)));
        }
        BannedWordScope scope = parse(errors, "scope", request == null ? null : request.scope(),
                BannedWordScope.class, true);
        BannedWordAction action = parse(errors, "action", request == null ? null : request.action(),
                BannedWordAction.class, true);
        requireCombination(errors, scope, action);
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
        }
        if (repository.existsByWord(word)) {
            throw exists(word);
        }
        BannedWord saved;
        try {
            saved = repository.saveAndFlush(new BannedWord(userRepository.getReferenceById(adminId), word, scope,
                    action));
        } catch (DataIntegrityViolationException e) {
            throw exists(word);
        }
        auditService.record(adminId, AuditActions.BANNED_WORD_CREATE, AuditActions.TARGET_BANNED_WORD, saved.getId(),
                null, snapshot(saved), requestIp);
        events.publishEvent(new BannedWordsChangedEvent());
        return BannedWordResponse.of(repository.findWithCreator(saved.getId()).orElseThrow());
    }

    @Transactional
    public BannedWordResponse update(long adminId, long id, BannedWordRequest request, String requestIp) {
        BannedWord word = require(id);
        List<FieldError> errors = new ArrayList<>();
        BannedWordScope scope = parse(errors, "scope", request == null ? null : request.scope(),
                BannedWordScope.class, false);
        BannedWordAction action = parse(errors, "action", request == null ? null : request.action(),
                BannedWordAction.class, false);
        requireCombination(errors, scope == null ? word.getScope() : scope,
                action == null ? word.getAction() : action);
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
        }
        Map<String, Object> before = snapshot(word);
        word.change(scope, action);
        repository.flush();
        Map<String, Object> after = snapshot(word);
        if (!before.equals(after)) {
            auditService.record(adminId, AuditActions.BANNED_WORD_UPDATE, AuditActions.TARGET_BANNED_WORD, id,
                    before, after, requestIp);
            events.publishEvent(new BannedWordsChangedEvent());
        }
        return BannedWordResponse.of(word);
    }

    @Transactional
    public void delete(long adminId, long id, String requestIp) {
        BannedWord word = require(id);
        Map<String, Object> before = snapshot(word);
        repository.delete(word);
        auditService.record(adminId, AuditActions.BANNED_WORD_DELETE, AuditActions.TARGET_BANNED_WORD, id, before,
                null, requestIp);
        events.publishEvent(new BannedWordsChangedEvent());
    }

    private BannedWord require(long id) {
        return repository.findWithCreator(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.BANNED_WORD_NOT_FOUND, "Banned word not found: " + id));
    }

    private static void requireCombination(List<FieldError> errors, BannedWordScope scope, BannedWordAction action) {
        if (scope == BannedWordScope.NAME && action == BannedWordAction.MASK) {
            errors.add(FieldError.of("action", "INVALID"));
        }
    }

    private static <E extends Enum<E>> E parse(List<FieldError> errors, String field, String raw, Class<E> type,
            boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.add(FieldError.of(field, "REQUIRED"));
            }
            return null;
        }
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        errors.add(new FieldError(field, "INVALID",
                Map.of("allowed", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList())));
        return null;
    }

    private static Map<String, Object> snapshot(BannedWord word) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("word", word.getWord());
        values.put("scope", word.getScope().name());
        values.put("action", word.getAction().name());
        return values;
    }

    private static BusinessException exists(String word) {
        return new BusinessException(ErrorCode.BANNED_WORD_EXISTS, "Banned word exists: " + word);
    }
}
