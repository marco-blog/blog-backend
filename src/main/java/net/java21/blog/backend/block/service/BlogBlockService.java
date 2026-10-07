package net.java21.blog.backend.block.service;

import java.time.Clock;
import java.util.Optional;

import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.block.domain.BlogBlockId;
import net.java21.blog.backend.block.dto.BlockedUserResponse;
import net.java21.blog.backend.block.repository.BlockQueryRepository;
import net.java21.blog.backend.block.repository.BlogBlockRepository;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.subscription.service.SubscriptionService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 회원 차단(004 FR-146, research B13). 모두 블로그 주인만(001 {@link BlogAccess}).
 * <ul>
 *   <li>차단은 멱등(이미 차단했으면 그대로). 자기 자신은 422 {@code CANNOT_BLOCK_SELF}, 없는(또는 탈퇴한) 회원은 404
 *       {@code USER_NOT_FOUND}. 같은 트랜잭션에서 그 회원의 이 블로그 구독을 지우고 구독자 수를 줄인다. 알림은 보내지 않는다.</li>
 *   <li>해제는 차단하지 않은 회원이면 404 {@code BLOCK_NOT_FOUND}.</li>
 *   <li>이미 쓴 댓글·방명록은 그대로 둔다(주인이 따로 지울 수 있다).</li>
 * </ul>
 */
@Service
public class BlogBlockService {

    private final BlogAccess blogAccess;
    private final BlogBlockRepository blockRepository;
    private final BlockQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    public BlogBlockService(BlogAccess blogAccess, BlogBlockRepository blockRepository,
            BlockQueryRepository queryRepository, UserRepository userRepository,
            SubscriptionService subscriptionService, Clock clock) {
        this.blogAccess = blogAccess;
        this.blockRepository = blockRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<BlockedUserResponse> list(long ownerId, String handle, Pageable pageable) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, ownerId);
        return queryRepository.findPage(blog.getId(), pageable).map(row -> new BlockedUserResponse(
                new BlockedUserResponse.BlockedUser(row.userId(), row.nickname(), Media.urlOf(row.profileMediaKey())),
                row.blockedAt()));
    }

    @Transactional
    public BlockedUserResponse block(long ownerId, String handle, Long userId) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, ownerId);
        if (userId != null && userId == ownerId) {
            throw new BusinessException(ErrorCode.CANNOT_BLOCK_SELF, "Cannot block yourself: " + handle);
        }
        User target = Optional.ofNullable(userId).flatMap(userRepository::findById)
                .filter(u -> u.getStatus() != UserStatus.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId));
        BlogBlockId id = new BlogBlockId(blog.getId(), target.getId());
        BlogBlock block = blockRepository.findById(id)
                .orElseGet(() -> blockRepository.save(new BlogBlock(blog, target, clock.instant())));
        subscriptionService.removeForBlock(blog.getId(), target.getId());
        return response(target, block);
    }

    @Transactional
    public void unblock(long ownerId, String handle, Long userId) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, ownerId);
        BlogBlockId id = new BlogBlockId(blog.getId(), userId);
        BlogBlock block = blockRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOCK_NOT_FOUND, "Block not found: " + userId));
        blockRepository.delete(block);
    }

    private static BlockedUserResponse response(User user, BlogBlock block) {
        return new BlockedUserResponse(new BlockedUserResponse.BlockedUser(user.getId(), user.getNickname(),
                user.profileImageUrl()), block.getCreatedAt());
    }
}
