package net.java21.blog.backend.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/** 서비스 단위 테스트에서 {@link BusinessException}의 오류 코드를 확인한다. */
public final class BusinessAssertions {

    private BusinessAssertions() {
    }

    public static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }
}
