package net.java21.blog.backend.block.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.block.domain.BlogBlockId;
import net.java21.blog.backend.block.dto.BlockedUserResponse;
import net.java21.blog.backend.block.repository.BlockQueryRepository;
import net.java21.blog.backend.block.repository.BlockQueryRepository.BlockRow;
import net.java21.blog.backend.block.repository.BlogBlockRepository;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.subscription.service.SubscriptionService;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 차단 규칙(T112, 004 FR-146): 주인만, 자기 자신 422, 없는·탈퇴 회원 404, 멱등 생성, 구독 정리, 해제 404, 목록 매핑.
 */
@ExtendWith(MockitoExtension.class)
class BlogBlockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private BlogBlockRepository blockRepository;
    @Mock
    private BlockQueryRepository queryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SubscriptionService subscriptionService;

    private BlogBlockService service;
    private Blog blog;
    private User troll;

    @BeforeEach
    void setUp() {
        service = new BlogBlockService(blogAccess, blockRepository, queryRepository, userRepository,
                subscriptionService, new MutableClock(NOW));
        User owner = TestEntities.user(1L);
        blog = TestEntities.blog(10L, owner, "marco");
        troll = TestEntities.user(2L);
        lenient().when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
        lenient().when(blogAccess.requireOwnedActiveBlog("marco", 2L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "not owner"));
    }

    @Test
    void blockSavesRowAndRemovesSubscription() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(troll));
        when(blockRepository.findById(new BlogBlockId(10L, 2L))).thenReturn(Optional.empty());
        when(blockRepository.save(any(BlogBlock.class))).thenAnswer(i -> i.getArgument(0));

        BlockedUserResponse response = service.block(1L, "marco", 2L);

        ArgumentCaptor<BlogBlock> saved = ArgumentCaptor.forClass(BlogBlock.class);
        verify(blockRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new BlogBlockId(10L, 2L));
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(subscriptionService).removeForBlock(10L, 2L);
        assertThat(response.user().userId()).isEqualTo(2L);
        assertThat(response.user().nickname()).isEqualTo(troll.getNickname());
        assertThat(response.blockedAt()).isEqualTo(NOW);
    }

    @Test
    void blockIsIdempotentAndKeepsOriginalTime() {
        Instant earlier = NOW.minusSeconds(3600);
        when(userRepository.findById(2L)).thenReturn(Optional.of(troll));
        when(blockRepository.findById(new BlogBlockId(10L, 2L)))
                .thenReturn(Optional.of(new BlogBlock(blog, troll, earlier)));

        assertThat(service.block(1L, "marco", 2L).blockedAt()).isEqualTo(earlier);
        verify(blockRepository, never()).save(any());
    }

    @Test
    void blockRejectsSelfMissingWithdrawnAndNonOwner() {
        assertCode(() -> service.block(1L, "marco", 1L), ErrorCode.CANNOT_BLOCK_SELF);
        when(userRepository.findById(3L)).thenReturn(Optional.empty());
        assertCode(() -> service.block(1L, "marco", 3L), ErrorCode.USER_NOT_FOUND);
        assertCode(() -> service.block(1L, "marco", null), ErrorCode.USER_NOT_FOUND);
        User gone = TestEntities.user(4L);
        gone.withdraw(NOW);
        when(userRepository.findById(4L)).thenReturn(Optional.of(gone));
        assertCode(() -> service.block(1L, "marco", 4L), ErrorCode.USER_NOT_FOUND);
        assertCode(() -> service.block(2L, "marco", 3L), ErrorCode.FORBIDDEN);
        verify(blockRepository, never()).save(any());
        verify(subscriptionService, never()).removeForBlock(any(), any());
    }

    @Test
    void unblockDeletesOr404() {
        BlogBlock block = new BlogBlock(blog, troll, NOW);
        when(blockRepository.findById(new BlogBlockId(10L, 2L))).thenReturn(Optional.of(block));
        service.unblock(1L, "marco", 2L);
        verify(blockRepository).delete(block);

        when(blockRepository.findById(new BlogBlockId(10L, 5L))).thenReturn(Optional.empty());
        assertCode(() -> service.unblock(1L, "marco", 5L), ErrorCode.BLOCK_NOT_FOUND);
        assertCode(() -> service.unblock(2L, "marco", 2L), ErrorCode.FORBIDDEN);
    }

    @Test
    void listMapsRowsWithProfileUrl() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(queryRepository.findPage(10L, pageable)).thenReturn(new PageImpl<>(
                List.of(new BlockRow(2L, "troll", "pk", NOW), new BlockRow(3L, "spam", null, NOW)), pageable, 2));

        List<BlockedUserResponse> list = service.list(1L, "marco", pageable).getContent();

        assertThat(list.get(0)).isEqualTo(
                new BlockedUserResponse(new BlockedUserResponse.BlockedUser(2L, "troll", "/media/pk"), NOW));
        assertThat(list.get(1).user().profileImageUrl()).isNull();
        assertCode(() -> service.list(2L, "marco", pageable), ErrorCode.FORBIDDEN);
    }
}
