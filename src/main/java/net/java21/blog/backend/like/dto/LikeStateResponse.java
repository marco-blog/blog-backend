package net.java21.blog.backend.like.dto;

/** 좋아요 상태({@code PUT·DELETE /me/likes/{postId}} 응답, 002 contracts/api.md). */
public record LikeStateResponse(Long postId, boolean liked, int likeCount) {
}
