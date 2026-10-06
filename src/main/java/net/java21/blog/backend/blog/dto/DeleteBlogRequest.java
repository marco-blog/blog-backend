package net.java21.blog.backend.blog.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code DELETE /blogs/{handle}}: 되돌릴 수 없으므로 비밀번호를 다시 확인한다. */
public record DeleteBlogRequest(@NotBlank String password) {
}
