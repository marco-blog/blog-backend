package net.java21.blog.backend.admin.external.dto;

import java.util.List;

import net.java21.blog.backend.admin.external.ClassificationReviewService;

/** {@code POST /admin/classification-reviews/confirm-batch}: 1~50개. */
public record ConfirmBatchRequest(List<ClassificationReviewService.Item> items) {
}
