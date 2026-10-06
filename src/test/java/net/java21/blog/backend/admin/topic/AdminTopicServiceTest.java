package net.java21.blog.backend.admin.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.topic.dto.AdminTopicNode;
import net.java21.blog.backend.admin.topic.dto.CreateTopicRequest;
import net.java21.blog.backend.admin.topic.dto.TopicOrderRequest;
import net.java21.blog.backend.admin.topic.dto.UpdateTopicRequest;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.repository.TopicRow;
import net.java21.blog.backend.topic.service.TopicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** 관리자 주제 관리(003 T082, FR-079, AS3): 추가·수정·순서와 작업 기록·포털 캐시 비우기. */
@ExtendWith(MockitoExtension.class)
class AdminTopicServiceTest {

    private static final long ADMIN = 9L;
    private static final String IP = "203.0.113.9";
    private static final Instant T = Instant.parse("2026-10-06T00:00:00Z");

    @Mock
    private TopicRepository topicRepository;
    @Mock
    private TopicQueryRepository topicQueryRepository;
    @Mock
    private TopicService topicService;
    @Mock
    private SystemSettingsService settings;
    @Mock
    private AdminAuditService auditService;
    @Mock
    private ApplicationEventPublisher events;

    private AdminTopicService service;
    private Topic major;
    private Topic minor;
    private final List<TopicRow> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AdminTopicService(topicRepository, topicQueryRepository, topicService, settings, auditService,
                events);
        major = TestEntities.topic(1L, null, "knowledge");
        minor = TestEntities.topic(11L, major, "it-internet");
        rows.add(row(1L, null, "knowledge", false, false, false));
        rows.add(row(11L, 1L, "it-internet", false, false, false));
        lenient().when(topicQueryRepository.findAll()).thenReturn(rows);
        lenient().when(topicService.recentPostCounts()).thenReturn(Map.of(11L, 3L));
        lenient().when(settings.topicAutoHideThreshold()).thenReturn(2);
    }

    // ---- 트리 ----

    @Test
    void treeShowsHiddenTopicsCountsAndTabState() {
        rows.add(row(12L, 1L, "mobile", true, false, false));
        rows.add(row(2L, null, "sports", true, false, true));
        rows.add(row(21L, 2L, "golf", false, true, true));
        when(topicService.recentPostCounts()).thenReturn(Map.of(11L, 3L, 12L, 5L, 21L, 9L));

        List<AdminTopicNode> tree = service.tree();

        assertThat(tree).extracting(AdminTopicNode::slug).containsExactly("knowledge", "sports");
        AdminTopicNode knowledge = tree.get(0);
        assertThat(knowledge.recentPostCount()).isEqualTo(3); // 숨긴 소분류(mobile)는 합에서 뺀다
        assertThat(knowledge.onTab()).isTrue();
        assertThat(knowledge.children()).extracting(AdminTopicNode::slug).containsExactly("it-internet", "mobile");
        AdminTopicNode mobile = knowledge.children().get(1);
        assertThat(mobile.adminHidden()).isTrue();
        assertThat(mobile.effectiveHidden()).isTrue();
        assertThat(mobile.onTab()).isFalse();
        assertThat(mobile.recentPostCount()).isEqualTo(5);
        AdminTopicNode sports = tree.get(1);
        assertThat(sports.effectiveHidden()).isTrue();
        assertThat(sports.onTab()).isFalse();
        assertThat(sports.pinnedOnTab()).isTrue();
        assertThat(sports.children().get(0).effectiveHidden()).isTrue();
        assertThat(sports.children().get(0).adminHidden()).isFalse();
        assertThat(sports.children().get(0).onTab()).isFalse();
    }

    // ---- 추가 ----

    @Test
    void createAddsMinorAtTheEndOfItsParentAndRecordsIt() {
        when(topicRepository.findById(1L)).thenReturn(Optional.of(major));
        when(topicRepository.existsBySlug("mobile")).thenReturn(false);
        Topic sibling = TestEntities.topic(13L, major, "science");
        sibling.moveTo(4);
        when(topicRepository.findChildren(1L)).thenReturn(List.of(minor, sibling));
        when(topicRepository.saveAndFlush(any(Topic.class))).thenAnswer(inv -> {
            Topic saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 12L);
            rows.add(row(12L, 1L, "mobile", false, false, false));
            return saved;
        });

        AdminTopicNode node = service.create(ADMIN, new CreateTopicRequest(1L, " mobile ", names("모바일"), null), IP);

        assertThat(node.id()).isEqualTo(12L);
        ArgumentCaptor<Topic> saved = ArgumentCaptor.forClass(Topic.class);
        verify(topicRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSortOrder()).isEqualTo(5);
        assertThat(saved.getValue().getParent()).isSameAs(major);
        assertThat(saved.getValue().getNames().ko()).isEqualTo("모바일");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(ADMIN), eq("TOPIC_CREATE"), eq("TOPIC"), eq(12L), eq(null), after.capture(),
                eq(IP));
        assertThat(after.getValue()).containsEntry("slug", "mobile").containsEntry("parentId", 1L)
                .containsEntry("sortOrder", 5);
        verify(events).publishEvent(any(PortalChangedEvent.class));
    }

    @Test
    void createMajorStartsAtZeroWhenNoSiblings() {
        when(topicRepository.existsBySlug("new-major")).thenReturn(false);
        when(topicRepository.findChildren(null)).thenReturn(List.of());
        when(topicRepository.saveAndFlush(any(Topic.class))).thenAnswer(inv -> {
            Topic saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 3L);
            rows.add(row(3L, null, "new-major", false, false, false));
            return saved;
        });

        AdminTopicNode node = service.create(ADMIN, new CreateTopicRequest(null, "new-major", names("새"), "#AABBCC"),
                IP);

        assertThat(node.parentId()).isNull();
        ArgumentCaptor<Topic> saved = ArgumentCaptor.forClass(Topic.class);
        verify(topicRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSortOrder()).isZero();
        assertThat(saved.getValue().getCardColor()).isEqualTo("#AABBCC");
    }

    @Test
    void createValidatesSlugNamesAndColor() {
        Map<String, String> badNames = new LinkedHashMap<>();
        badNames.put("ko", "가".repeat(51));
        badNames.put("en", " ");
        badNames.put("ja", "ja");
        badNames.put("fr", "fr");

        assertFieldErrors(() -> service.create(ADMIN, new CreateTopicRequest(null, "Bad_Slug", badNames, "red"), IP),
                new FieldError("slug", "INVALID_FORMAT", Map.of("min", 2, "max", 40)),
                new FieldError("names.ko", "TOO_LONG", Map.of("max", 50)),
                FieldError.of("names.en", "REQUIRED"), FieldError.of("names.zh-CN", "REQUIRED"),
                FieldError.of("names.fr", "INVALID"), FieldError.of("cardColor", "INVALID_FORMAT"));
        assertFieldErrors(() -> service.create(ADMIN, new CreateTopicRequest(null, "a", names("x"), null), IP),
                new FieldError("slug", "INVALID_FORMAT", Map.of("min", 2, "max", 40)));
        assertFieldErrors(() -> service.create(ADMIN, new CreateTopicRequest(null, null, null, null), IP),
                FieldError.of("slug", "REQUIRED"), FieldError.of("names.ko", "REQUIRED"),
                FieldError.of("names.en", "REQUIRED"), FieldError.of("names.ja", "REQUIRED"),
                FieldError.of("names.zh-CN", "REQUIRED"));
        verify(topicRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsDepthMissingParentAndTakenSlug() {
        when(topicRepository.findById(11L)).thenReturn(Optional.of(minor));
        when(topicRepository.findById(99L)).thenReturn(Optional.empty());
        when(topicRepository.findById(1L)).thenReturn(Optional.of(major));
        when(topicRepository.existsBySlug("mobile")).thenReturn(true);

        assertCode(() -> service.create(ADMIN, new CreateTopicRequest(11L, "deep", names("d"), null), IP),
                ErrorCode.TOPIC_DEPTH_EXCEEDED);
        assertCode(() -> service.create(ADMIN, new CreateTopicRequest(99L, "orphan", names("o"), null), IP),
                ErrorCode.TOPIC_NOT_FOUND);
        assertCode(() -> service.create(ADMIN, new CreateTopicRequest(1L, "mobile", names("m"), null), IP),
                ErrorCode.TOPIC_SLUG_TAKEN);
        verify(auditService, never()).record(anyLong(), anyString(), anyString(), any(), any(), any(), any());
    }

    // ---- 수정 ----

    @Test
    void updateRenamesSomeLanguagesAndRecordsEachChangedItem() {
        when(topicRepository.findById(11L)).thenReturn(Optional.of(minor));
        UpdateTopicRequest request = new UpdateTopicRequest();
        request.setNames(Map.of("ja", "新しい"));
        request.setCardColor("#112233");
        request.setAdminHidden(true);
        request.setPinnedOnTab(true);

        service.update(ADMIN, 11L, request, IP);

        assertThat(minor.getNames().ja()).isEqualTo("新しい");
        assertThat(minor.getNames().ko()).isEqualTo("it-internet-ko");
        assertThat(minor.getCardColor()).isEqualTo("#112233");
        assertThat(minor.isAdminHidden()).isTrue();
        assertThat(minor.isPinnedOnTab()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(ADMIN), eq("TOPIC_UPDATE"), eq("TOPIC"), eq(11L), any(), after.capture(),
                eq(IP));
        assertThat(after.getValue()).containsKeys("names", "cardColor");
        verify(auditService).record(ADMIN, "TOPIC_HIDE", "TOPIC", 11L, Map.of("adminHidden", false),
                Map.of("adminHidden", true), IP);
        verify(auditService).record(ADMIN, "TOPIC_PIN", "TOPIC", 11L, Map.of("pinnedOnTab", false),
                Map.of("pinnedOnTab", true), IP);
        verify(events).publishEvent(any(PortalChangedEvent.class));
    }

    @Test
    void updateUnhidesUnpinsAndClearsColor() {
        minor.hide();
        minor.pin();
        minor.changeColor("#000000");
        when(topicRepository.findById(11L)).thenReturn(Optional.of(minor));
        UpdateTopicRequest request = new UpdateTopicRequest();
        request.setCardColor(null);
        request.setAdminHidden(false);
        request.setPinnedOnTab(false);

        service.update(ADMIN, 11L, request, IP);

        assertThat(minor.getCardColor()).isNull();
        verify(auditService).record(ADMIN, "TOPIC_UNHIDE", "TOPIC", 11L, Map.of("adminHidden", true),
                Map.of("adminHidden", false), IP);
        verify(auditService).record(ADMIN, "TOPIC_UNPIN", "TOPIC", 11L, Map.of("pinnedOnTab", true),
                Map.of("pinnedOnTab", false), IP);
    }

    @Test
    void updateWithoutChangesRecordsNothing() {
        when(topicRepository.findById(11L)).thenReturn(Optional.of(minor));
        UpdateTopicRequest request = new UpdateTopicRequest();
        request.setAdminHidden(false);
        request.setNames(Map.of("ko", "it-internet-ko"));

        AdminTopicNode node = service.update(ADMIN, 11L, request, IP);

        assertThat(node.id()).isEqualTo(11L);
        verify(auditService, never()).record(anyLong(), anyString(), anyString(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void updateValidatesAndRejectsMissingTopic() {
        when(topicRepository.findById(11L)).thenReturn(Optional.of(minor));
        when(topicRepository.findById(99L)).thenReturn(Optional.empty());
        UpdateTopicRequest bad = new UpdateTopicRequest();
        bad.setNames(Map.of("ko", ""));
        bad.setCardColor("blue");
        bad.setAdminHidden(null);
        bad.setPinnedOnTab(null);
        assertFieldErrors(() -> service.update(ADMIN, 11L, bad, IP), FieldError.of("names.ko", "REQUIRED"),
                FieldError.of("cardColor", "INVALID_FORMAT"), FieldError.of("adminHidden", "REQUIRED"),
                FieldError.of("pinnedOnTab", "REQUIRED"));
        UpdateTopicRequest nullNames = new UpdateTopicRequest();
        nullNames.setNames(null);
        assertFieldErrors(() -> service.update(ADMIN, 11L, nullNames, IP), FieldError.of("names", "REQUIRED"));
        assertCode(() -> service.update(ADMIN, 99L, new UpdateTopicRequest(), IP), ErrorCode.TOPIC_NOT_FOUND);
    }

    // ---- 순서 ----

    @Test
    void reorderNeedsTheWholeChildSetAndRecordsBeforeAndAfter() {
        Topic second = TestEntities.topic(12L, major, "mobile");
        second.moveTo(1);
        when(topicRepository.existsById(1L)).thenReturn(true);
        when(topicRepository.findChildren(1L)).thenReturn(List.of(minor, second));

        service.reorder(ADMIN, new TopicOrderRequest(1L, List.of(12L, 11L)), IP);

        assertThat(second.getSortOrder()).isZero();
        assertThat(minor.getSortOrder()).isEqualTo(1);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("parentId", 1L);
        before.put("ids", List.of(11L, 12L));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("parentId", 1L);
        after.put("ids", List.of(12L, 11L));
        verify(auditService).record(ADMIN, "TOPIC_REORDER", "TOPIC", 1L, before, after, IP);
        verify(events).publishEvent(any(PortalChangedEvent.class));

        for (List<Long> wrong : List.of(List.of(12L), List.of(12L, 11L, 13L), List.of(12L, 12L), List.of(12L, 99L))) {
            assertFieldErrors(() -> service.reorder(ADMIN, new TopicOrderRequest(1L, wrong), IP),
                    FieldError.of("ids", "INVALID"));
        }
        assertFieldErrors(() -> service.reorder(ADMIN, new TopicOrderRequest(1L, null), IP),
                FieldError.of("ids", "REQUIRED"));
        when(topicRepository.existsById(99L)).thenReturn(false);
        assertCode(() -> service.reorder(ADMIN, new TopicOrderRequest(99L, List.of()), IP), ErrorCode.TOPIC_NOT_FOUND);
    }

    @Test
    void reorderMajorsWithSameOrderRecordsNothing() {
        when(topicRepository.findChildren(null)).thenReturn(List.of(major));

        List<AdminTopicNode> tree = service.reorder(ADMIN, new TopicOrderRequest(null, List.of(1L)), IP);

        assertThat(tree).hasSize(1);
        verify(auditService, never()).record(anyLong(), anyString(), anyString(), any(), any(), any(), any());
    }

    private static Map<String, String> names(String ko) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("ko", ko);
        names.put("en", ko + "-en");
        names.put("ja", ko + "-ja");
        names.put("zh-CN", ko + "-zh");
        return names;
    }

    private static TopicRow row(long id, Long parentId, String slug, boolean hidden, boolean parentHidden,
            boolean pinned) {
        return new TopicRow(id, parentId, slug, slug + "-ko", slug + "-en", slug + "-ja", slug + "-zh", 0, hidden,
                parentHidden, pinned, null, T, T);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }

    private static void assertFieldErrors(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            FieldError... errors) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).satisfies(e -> {
            BusinessException be = (BusinessException) e;
            assertThat(be.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(be.fieldErrors()).containsExactlyInAnyOrder(errors);
        });
    }
}
