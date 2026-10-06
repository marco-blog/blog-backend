package net.java21.blog.backend.notification.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.notification.dto.BulkNotificationRequest;
import net.java21.blog.backend.notification.dto.BulkNotificationResponse;
import net.java21.blog.backend.notification.dto.NotificationBulkAction;
import net.java21.blog.backend.notification.dto.NotificationResponse;
import net.java21.blog.backend.notification.service.NotificationService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 알림 API(T045, 002 contracts/api.md 알림 절): 응답 형식, 401·404·400. */
@WebMvcTest(NotificationController.class)
@Import(WebMvcTestSupport.class)
class NotificationControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private NotificationService service;

    private static NotificationResponse notification(boolean read) {
        return new NotificationResponse(501L, "NEW_COMMENT", new NotificationResponse.Actor(7L, "독자", null, false),
                new NotificationResponse.BlogRef("marco", "마르코의 블로그"), "COMMENT", 3001L,
                Map.of("postId", 123, "postTitle", "첫 글"), read, NOW);
    }

    @Test
    void listsMyNotificationsAsPage() throws Exception {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(service.list(eq(9L), pageable.capture())).thenAnswer(invocation -> new PageImpl<>(
                List.of(notification(false)), invocation.getArgument(1), 41));

        mvc.perform(get("/api/v1/me/notifications").param("page", "2").cookie(authCookies.user(9L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(41))
                .andExpect(jsonPath("$.result[0].id").value(501))
                .andExpect(jsonPath("$.result[0].type").value("NEW_COMMENT"))
                .andExpect(jsonPath("$.result[0].actor.userId").value(7))
                .andExpect(jsonPath("$.result[0].actor.nickname").value("독자"))
                .andExpect(jsonPath("$.result[0].actor.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result[0].actor.withdrawn").value(false))
                .andExpect(jsonPath("$.result[0].blog.handle").value("marco"))
                .andExpect(jsonPath("$.result[0].blog.title").value("마르코의 블로그"))
                .andExpect(jsonPath("$.result[0].targetType").value("COMMENT"))
                .andExpect(jsonPath("$.result[0].targetId").value(3001))
                .andExpect(jsonPath("$.result[0].params.postId").value(123))
                .andExpect(jsonPath("$.result[0].params.postTitle").value("첫 글"))
                .andExpect(jsonPath("$.result[0].read").value(false))
                .andExpect(jsonPath("$.result[0].createdAt").value("2026-10-06T04:24:19Z"));
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    void readReturnsReadNotification() throws Exception {
        when(service.read(9L, 501L)).thenReturn(notification(true));

        mvc.perform(post("/api/v1/me/notifications/501/read").cookie(authCookies.user(9L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(501))
                .andExpect(jsonPath("$.result.read").value(true));
    }

    @Test
    void readOfOthersIsNotFound() throws Exception {
        when(service.read(9L, 502L)).thenThrow(new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND, "x"));

        mvc.perform(post("/api/v1/me/notifications/502/read").cookie(authCookies.user(9L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void bulkMarksRead() throws Exception {
        when(service.bulk(9L, new BulkNotificationRequest(NotificationBulkAction.MARK_READ, List.of(1L, 2L))))
                .thenReturn(new BulkNotificationResponse(2));
        when(service.bulk(9L, new BulkNotificationRequest(NotificationBulkAction.MARK_READ, null)))
                .thenReturn(new BulkNotificationResponse(5));

        mvc.perform(post("/api/v1/me/notifications/bulk").cookie(authCookies.user(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_READ\",\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.updated").value(2));
        mvc.perform(post("/api/v1/me/notifications/bulk").cookie(authCookies.user(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.updated").value(5));
    }

    @Test
    void bulkValidation() throws Exception {
        String tooMany = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));
        mvc.perform(post("/api/v1/me/notifications/bulk").cookie(authCookies.user(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_READ\",\"ids\":[" + tooMany + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("ids"));
        mvc.perform(post("/api/v1/me/notifications/bulk").cookie(authCookies.user(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DELETE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/me/notifications/bulk").cookie(authCookies.user(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("action"));
        verify(service, never()).bulk(anyLong(), any());
    }

    @Test
    void needsLogin() throws Exception {
        mvc.perform(get("/api/v1/me/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/me/notifications/1/read"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/notifications/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_READ\"}"))
                .andExpect(status().isUnauthorized());
    }
}
