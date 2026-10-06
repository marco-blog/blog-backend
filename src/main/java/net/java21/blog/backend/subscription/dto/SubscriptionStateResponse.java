package net.java21.blog.backend.subscription.dto;

/** 구독 상태({@code PUT·DELETE /me/subscriptions/{handle}} 응답, 002 contracts/api.md). */
public record SubscriptionStateResponse(String handle, boolean subscribed, int subscriberCount) {
}
