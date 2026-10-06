package net.java21.blog.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.LoginHistory;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.dto.LoginHistoryResponse;
import net.java21.blog.backend.user.repository.LoginHistoryQueryRepository;
import net.java21.blog.backend.user.repository.LoginHistoryRepository;
import net.java21.blog.backend.user.repository.LoginHistoryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 로그인 기록(T124, FR-139, quickstart #21): 성공·실패마다 기록(없는 이메일은 회원 없음), IP는 암호화 컬럼에 저장되는 엔티티 필드,
 * User-Agent 300자 자르기, 조회 때 IP 일부 가림(IPv4 뒤 두 자리, IPv6 앞 3블록만 — tasks.md "구현 전 결정 사항" 10번).
 */
@ExtendWith(MockitoExtension.class)
class LoginHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");

    @Mock
    private LoginHistoryRepository repository;
    @Mock
    private LoginHistoryQueryRepository queryRepository;

    private LoginHistoryService service;

    @BeforeEach
    void setUp() {
        service = new LoginHistoryService(repository, queryRepository, new MutableClock(NOW));
    }

    @Test
    void recordsSuccessWithMemberIpAndUserAgent() {
        User user = TestEntities.user(7L);

        service.record(user, true, new ClientInfo("211.234.56.78", "Mozilla/5.0"));

        LoginHistory saved = saved();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getIp()).isEqualTo("211.234.56.78");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void recordsFailureOfUnknownEmailWithoutMember() {
        service.record(null, false, new ClientInfo("10.0.0.1", null));

        LoginHistory saved = saved();
        assertThat(saved.getUser()).isNull();
        assertThat(saved.isSuccess()).isFalse();
        assertThat(saved.getUserAgent()).isNull();
    }

    @Test
    void cutsUserAgentAt300Characters() {
        service.record(null, false, new ClientInfo("10.0.0.1", "a".repeat(301)));

        assertThat(saved().getUserAgent()).hasSize(LoginHistory.USER_AGENT_MAX);
    }

    @Test
    void blankClientValuesAreStoredAsNull() {
        service.record(null, true, new ClientInfo(" ", " "));

        LoginHistory saved = saved();
        assertThat(saved.getIp()).isNull();
        assertThat(saved.getUserAgent()).isNull();
    }

    @Test
    void listMasksIpAndKeepsPageInformation() {
        Pageable pageable = PageRequest.of(1, 2);
        Instant at = Instant.parse("2026-10-05T00:00:00Z");
        Page<LoginHistoryRow> rows = new PageImpl<>(List.of(
                new LoginHistoryRow(at, true, "211.234.56.78", "Mozilla/5.0"),
                new LoginHistoryRow(at.minusSeconds(60), false, "2001:0db8:85a3:0000:0000:8a2e:0370:7334", null)),
                pageable, 5);
        when(queryRepository.findByUserId(7L, pageable)).thenReturn(rows);

        Page<LoginHistoryResponse> page = service.list(7L, pageable);

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).containsExactly(
                new LoginHistoryResponse(at, true, "211.234.*.*", "Mozilla/5.0"),
                new LoginHistoryResponse(at.minusSeconds(60), false, "2001:db8:85a3::*", null));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "211.234.56.78, 211.234.*.*",
            "10.0.0.1, 10.0.*.*",
            "2001:db8:85a3::8a2e:370:7334, 2001:db8:85a3::*",
            "2001:0DB8:0000:0000::1, 2001:db8:0::*",
            "::1, 0:0:0::*",
            "::ffff:211.234.56.78, 211.234.*.*",
            "fe80::1%eth0, fe80:0:0::*",
            "not-an-ip, *",
            "999.1.1.1, *",
            "NULL, NULL"
    })
    void masksIp(String ip, String masked) {
        assertThat(LoginHistoryService.maskIp(ip)).isEqualTo(masked);
    }

    private LoginHistory saved() {
        ArgumentCaptor<LoginHistory> captor = ArgumentCaptor.forClass(LoginHistory.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
