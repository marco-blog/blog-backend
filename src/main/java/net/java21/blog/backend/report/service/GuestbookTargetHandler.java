package net.java21.blog.backend.report.service;

import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.repository.GuestbookEntryRepository;
import net.java21.blog.backend.guestbook.service.GuestbookVisibility;
import net.java21.blog.backend.report.domain.ReportTargetType;
import org.springframework.stereotype.Component;

/**
 * 방명록 글 신고·숨김(005 research M1·M4). 신고자는 블로그를 볼 수 있고(블로그·주인 ACTIVE, 방명록 열림) 004
 * {@link GuestbookVisibility#canRead}로 내용을 볼 수 있어야 한다. 숨김은 ACTIVE ↔ HIDDEN.
 */
@Component
public class GuestbookTargetHandler implements ReportTargetHandler {

    private final GuestbookEntryRepository entryRepository;

    public GuestbookTargetHandler(GuestbookEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    @Override
    public ReportTargetType type() {
        return ReportTargetType.GUESTBOOK;
    }

    @Override
    public ReportTarget resolveForReporter(long targetId, long reporterId) {
        GuestbookEntry entry = entryRepository.findWithBlogAndOwner(targetId)
                .filter(GuestbookEntry::isActive)
                .filter(e -> e.getBlog().isActive() && e.getBlog().getUser().isActive())
                .filter(e -> e.getBlog().isGuestbookEnabled() || e.getBlog().isOwnedBy(reporterId))
                .filter(e -> readable(e, reporterId))
                .orElseThrow(() -> ReportTargetErrors.notFound(type(), targetId));
        if (entry.isOwnedBy(reporterId)) {
            throw ReportTargetErrors.own(type(), targetId);
        }
        return target(entry);
    }

    @Override
    public ReportTarget resolveForAdmin(long targetId) {
        return target(require(targetId));
    }

    @Override
    public HideChange hide(long targetId) {
        GuestbookEntry entry = require(targetId);
        String before = entry.getStatus().name();
        boolean changed = entry.hide();
        return new HideChange(before, entry.getStatus().name(), changed);
    }

    @Override
    public HideChange unhide(long targetId) {
        GuestbookEntry entry = require(targetId);
        String before = entry.getStatus().name();
        boolean changed = entry.unhide();
        return new HideChange(before, entry.getStatus().name(), changed);
    }

    private static boolean readable(GuestbookEntry entry, long reporterId) {
        GuestbookEntry top = entry.isReply() ? entry.getParent() : entry;
        Long topAuthorId = top.getUser() == null ? null : top.getUser().getId();
        return GuestbookVisibility.canRead(top.isSecret(), topAuthorId, reporterId,
                entry.getBlog().getUser().getId());
    }

    private GuestbookEntry require(long targetId) {
        return entryRepository.findWithBlogAndOwner(targetId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> ReportTargetErrors.contentNotFound(type(), targetId));
    }

    private ReportTarget target(GuestbookEntry entry) {
        return new ReportTarget(type(), entry.getId(), entry.getUser(), entry.getBlog(), null,
                entry.getBlog().getHandle());
    }
}
