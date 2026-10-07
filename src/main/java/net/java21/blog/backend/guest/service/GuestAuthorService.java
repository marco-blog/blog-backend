package net.java21.blog.backend.guest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.AttemptTarget;
import net.java21.blog.backend.common.security.PasswordAttemptGuard;
import net.java21.blog.backend.common.text.PlainTextNormalizer;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guest.dto.GuestCredentials;
import net.java21.blog.backend.guest.dto.GuestWriteKind;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 비회원 댓글·방명록의 공통 규칙(004 research B6, FR-066). 댓글(US3)과 방명록(US1)이 같은 규칙을 쓴다.
 * <ul>
 *   <li>블로그의 {@code guestWriteEnabled}가 꺼져 있으면 401 {@code UNAUTHENTICATED}(front는 로그인 안내).</li>
 *   <li>이름 1~30자(제어 문자·앞뒤 공백 제거 후), 비밀번호 4~64자. 틀리면 400 {@code VALIDATION_FAILED}(field
 *       {@code guestName}·{@code guestPassword}, {@code REQUIRED}·{@code TOO_SHORT}·{@code TOO_LONG}).</li>
 *   <li>비밀번호는 BCrypt로만, IP는 엔티티가 암호화해 저장한다. 쓰기 속도는 {@link GuestWriteGuard}가 막는다.</li>
 *   <li>수정·삭제·내용 보기의 비밀번호 확인은 {@link PasswordAttemptGuard}로 시도를 센다(틀리면 403 {@code GUEST_PASSWORD_MISMATCH}).</li>
 * </ul>
 */
@Service
public class GuestAuthorService {

    public static final int NAME_MAX = 30;
    public static final int PASSWORD_MIN = 4;
    public static final int PASSWORD_MAX = 64;

    private final PasswordEncoder passwordEncoder;
    private final GuestWriteGuard writeGuard;
    private final PasswordAttemptGuard attemptGuard;

    public GuestAuthorService(PasswordEncoder passwordEncoder, GuestWriteGuard writeGuard,
            PasswordAttemptGuard attemptGuard) {
        this.passwordEncoder = passwordEncoder;
        this.writeGuard = writeGuard;
        this.attemptGuard = attemptGuard;
    }

    /** 이 블로그가 비회원 쓰기를 받는지. 아니면 401 {@code UNAUTHENTICATED}. */
    public void requireGuestAllowed(Blog blog) {
        if (!blog.isGuestWriteEnabled()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Guest writing is not allowed: " + blog.getHandle());
        }
    }

    /** 새 비회원 글의 작성자: 이름·비밀번호를 검증하고 쓰기 속도를 확인한 뒤 BCrypt 해시와 IP를 돌려준다. */
    public GuestCredentials newGuest(String name, String password, ClientInfo client, GuestWriteKind kind) {
        String cleanName = PlainTextNormalizer.singleLine(name);
        List<FieldError> errors = new ArrayList<>();
        if (cleanName.isEmpty()) {
            errors.add(FieldError.of("guestName", "REQUIRED"));
        } else if (cleanName.codePointCount(0, cleanName.length()) > NAME_MAX) {
            errors.add(new FieldError("guestName", "TOO_LONG", Map.of("max", NAME_MAX)));
        }
        if (password == null || password.isEmpty()) {
            errors.add(FieldError.of("guestPassword", "REQUIRED"));
        } else if (password.length() < PASSWORD_MIN) {
            errors.add(new FieldError("guestPassword", "TOO_SHORT", Map.of("min", PASSWORD_MIN)));
        } else if (password.length() > PASSWORD_MAX) {
            errors.add(new FieldError("guestPassword", "TOO_LONG", Map.of("max", PASSWORD_MAX)));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid guest author", errors);
        }
        String ip = client == null ? null : client.ip();
        writeGuard.check(kind, ip);
        return new GuestCredentials(cleanName, passwordEncoder.encode(password), ip);
    }

    /**
     * 비회원 글의 비밀번호 확인(수정·삭제·내용 보기). 막혀 있으면 429, 틀리면 실패를 세고 403 {@code GUEST_PASSWORD_MISMATCH}, 맞으면
     * 실패 수를 지운다.
     */
    public void verify(String passwordHash, String password, AttemptTarget target, String visitorKey, String ip) {
        attemptGuard.check(target, visitorKey, ip);
        if (password == null || password.isEmpty() || passwordHash == null
                || !passwordEncoder.matches(password, passwordHash)) {
            attemptGuard.recordFailure(target, visitorKey, ip);
            throw new BusinessException(ErrorCode.GUEST_PASSWORD_MISMATCH, "Guest password mismatch: " + target);
        }
        attemptGuard.recordSuccess(target, visitorKey, ip);
    }
}
