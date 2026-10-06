package net.java21.blog.backend.user.service;

import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인한 회원의 기본 정보와 내 블로그({@code GET /me}). 쿼리 2회(회원, 블로그 목록). */
@Service
public class MeQueryService {

    private final UserRepository userRepository;
    private final BlogQueryRepository blogQueryRepository;

    public MeQueryService(UserRepository userRepository, BlogQueryRepository blogQueryRepository) {
        this.userRepository = userRepository;
        this.blogQueryRepository = blogQueryRepository;
    }

    /** 회원이 없거나 정지·탈퇴했으면 401 {@code UNAUTHENTICATED}(front는 로그아웃 상태로 본다). */
    @Transactional(readOnly = true)
    public MeResponse me(long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Member is not active"));
        return new MeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getBio(), null,
                user.getRole().name(), user.getLocale(), user.getTimeZone(),
                blogQueryRepository.findActiveBlogLinks(user.getId()), null);
    }
}
