package net.java21.blog.backend.spam;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.spam.dto.BannedWordRequest;
import net.java21.blog.backend.admin.spam.dto.BannedWordResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.spam.repository.BannedWordQueryRepository;
import net.java21.blog.backend.spam.repository.BannedWordRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 005 T064: 금칙어 추가·변경·삭제(정규화, 1~50자, 중복 409, NAME+MASK 400, 없음 404), 작업 기록, 캐시 다시 읽기 이벤트. */
@ExtendWith(MockitoExtension.class)
class BannedWordServiceTest {

    private static final long ADMIN = 1L;

    @Mock
    private BannedWordRepository repository;
    @Mock
    private BannedWordQueryRepository queryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;
    @Mock
    private ApplicationEventPublisher events;

    private BannedWordService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new BannedWordService(repository, queryRepository, userRepository, auditService, events);
        admin = TestEntities.user(ADMIN, "admin@example.com", "{hash}", "관리자");
    }

    private BannedWord stored(long id, String word, BannedWordScope scope, BannedWordAction action) {
        return TestEntities.with(new BannedWord(admin, word, scope, action), "id", id);
    }

    @Test
    void createNormalizesAndRecords() {
        when(userRepository.getReferenceById(ADMIN)).thenReturn(admin);
        when(repository.saveAndFlush(any(BannedWord.class))).thenAnswer(i -> TestEntities.with(i.getArgument(0), "id", 9L));
        when(repository.findWithCreator(9L)).thenAnswer(i -> Optional.of(stored(9L, "bad", BannedWordScope.ALL,
                BannedWordAction.MASK)));

        BannedWordResponse created = service.create(ADMIN, new BannedWordRequest("  ＢＡＤ ", "ALL", "MASK"), "ip");

        assertThat(created.word()).isEqualTo("bad");
        assertThat(created.createdBy().nickname()).isEqualTo("관리자");
        ArgumentCaptor<BannedWord> saved = ArgumentCaptor.forClass(BannedWord.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getWord()).isEqualTo("bad");
        verify(auditService).record(eq(ADMIN), eq(AuditActions.BANNED_WORD_CREATE), eq(AuditActions.TARGET_BANNED_WORD),
                eq(9L), isNull(), eq(Map.of("word", "bad", "scope", "ALL", "action", "MASK")), eq("ip"));
        verify(events).publishEvent(any(BannedWordsChangedEvent.class));
    }

    @Test
    void createValidatesFields() {
        expectFields(new BannedWordRequest(" ", null, null), "word:REQUIRED", "scope:REQUIRED", "action:REQUIRED");
        expectFields(new BannedWordRequest("x".repeat(51), "NOPE", "REJECT"), "word:TOO_LONG", "scope:INVALID");
        expectFields(new BannedWordRequest("bad", "NAME", "MASK"), "action:INVALID");
        expectFields(null, "word:REQUIRED", "scope:REQUIRED", "action:REQUIRED");
        verifyNoInteractions(auditService, events);
    }

    @Test
    void duplicateIs409() {
        when(repository.existsByWord("bad")).thenReturn(true);
        assertCode(() -> service.create(ADMIN, new BannedWordRequest("BAD", "ALL", "REJECT"), null),
                ErrorCode.BANNED_WORD_EXISTS);

        when(repository.existsByWord("race")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));
        assertCode(() -> service.create(ADMIN, new BannedWordRequest("race", "ALL", "REJECT"), null),
                ErrorCode.BANNED_WORD_EXISTS);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void updateChangesOnlySentValuesAndRecordsDifference() {
        BannedWord word = stored(3L, "bad", BannedWordScope.CONTENT, BannedWordAction.REJECT);
        when(repository.findWithCreator(3L)).thenReturn(Optional.of(word));

        BannedWordResponse updated = service.update(ADMIN, 3L, new BannedWordRequest(null, null, "MASK"), "ip");
        assertThat(updated.action()).isEqualTo(BannedWordAction.MASK);
        assertThat(updated.scope()).isEqualTo(BannedWordScope.CONTENT);
        verify(auditService).record(eq(ADMIN), eq(AuditActions.BANNED_WORD_UPDATE), eq(AuditActions.TARGET_BANNED_WORD),
                eq(3L), eq(Map.of("word", "bad", "scope", "CONTENT", "action", "REJECT")),
                eq(Map.of("word", "bad", "scope", "CONTENT", "action", "MASK")), eq("ip"));
        verify(events).publishEvent(any(BannedWordsChangedEvent.class));

        // NAME으로 바꾸면서 MASK를 유지하면 400
        expectUpdate(3L, new BannedWordRequest(null, "NAME", null), "action:INVALID");
        // 바뀐 것이 없으면 기록하지 않는다.
        service.update(ADMIN, 3L, null, "ip");
        verify(auditService, org.mockito.Mockito.times(1)).record(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateAndDeleteMissingAre404() {
        when(repository.findWithCreator(99L)).thenReturn(Optional.empty());
        assertCode(() -> service.update(ADMIN, 99L, new BannedWordRequest(null, "ALL", null), null),
                ErrorCode.BANNED_WORD_NOT_FOUND);
        assertCode(() -> service.delete(ADMIN, 99L, null), ErrorCode.BANNED_WORD_NOT_FOUND);
    }

    @Test
    void deleteRecordsAndReloads() {
        BannedWord word = stored(4L, "bad", BannedWordScope.NAME, BannedWordAction.REJECT);
        when(repository.findWithCreator(4L)).thenReturn(Optional.of(word));
        service.delete(ADMIN, 4L, "ip");
        verify(repository).delete(word);
        verify(auditService).record(eq(ADMIN), eq(AuditActions.BANNED_WORD_DELETE), eq(AuditActions.TARGET_BANNED_WORD),
                eq(4L), eq(Map.of("word", "bad", "scope", "NAME", "action", "REJECT")), isNull(), eq("ip"));
        verify(events).publishEvent(any(BannedWordsChangedEvent.class));
    }

    @Test
    void listNormalizesQuery() {
        BannedWord word = stored(5L, "bad", BannedWordScope.ALL, BannedWordAction.REJECT);
        when(queryRepository.search(eq("bad"), any())).thenReturn(new PageImpl<>(List.of(word), PageRequest.of(0, 20), 1));
        assertThat(service.list(" ＢＡＤ ", PageRequest.of(0, 20)).getContent()).extracting(BannedWordResponse::id)
                .containsExactly(5L);
    }

    private void expectFields(BannedWordRequest request, String... fields) {
        assertThatThrownBy(() -> service.create(ADMIN, request, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(f -> f.field() + ":" + f.code()).containsExactly(fields);
                });
    }

    private void expectUpdate(long id, BannedWordRequest request, String... fields) {
        assertThatThrownBy(() -> service.update(ADMIN, id, request, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors()).extracting(f -> f.field() + ":" + f.code())
                                .containsExactly(fields));
    }
}
