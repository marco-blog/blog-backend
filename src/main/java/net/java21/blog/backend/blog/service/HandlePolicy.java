package net.java21.blog.backend.blog.service;

import java.util.regex.Pattern;

import net.java21.blog.backend.blog.ReservedHandles;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 블로그 주소(handle) 규칙(FR-002, research R5). 가입·새 블로그 만들기·주소 확인 API가 같이 쓴다.
 * <ol>
 *   <li>예약어(대소문자·앞뒤 공백 무시) → {@code HANDLE_RESERVED}</li>
 *   <li>{@value #REGEX}(3~20자, 영문 소문자·숫자·하이픈, 처음과 끝은 하이픈 아님), 연속 하이픈 금지 → {@code HANDLE_INVALID}</li>
 * </ol>
 * 중복({@code HANDLE_TAKEN})은 DB를 봐야 하므로 서비스가 따로 확인한다.
 */
@Component
public class HandlePolicy {

    static final String REGEX = "^[a-z0-9](?:[a-z0-9-]{1,18})[a-z0-9]$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    /** 규칙 위반 사유. 위반이 없으면 null. */
    public enum Violation {
        RESERVED,
        INVALID
    }

    public Violation violation(String handle) {
        if (handle == null) {
            return Violation.INVALID;
        }
        if (ReservedHandles.isReserved(handle)) {
            return Violation.RESERVED;
        }
        if (!PATTERN.matcher(handle).matches() || handle.contains("--")) {
            return Violation.INVALID;
        }
        return null;
    }

    /** 규칙을 어기면 422 {@code HANDLE_RESERVED} 또는 {@code HANDLE_INVALID}. */
    public void check(String handle) {
        Violation violation = violation(handle);
        if (violation == Violation.RESERVED) {
            throw new BusinessException(ErrorCode.HANDLE_RESERVED, "Reserved handle");
        }
        if (violation == Violation.INVALID) {
            throw new BusinessException(ErrorCode.HANDLE_INVALID, "Invalid handle");
        }
    }
}
