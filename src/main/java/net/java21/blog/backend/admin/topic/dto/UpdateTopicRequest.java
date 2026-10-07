package net.java21.blog.backend.admin.topic.dto;

import java.util.Map;

/**
 * 주제 수정({@code PATCH /admin/topics/{id}}, JSON Merge Patch). 보낸 필드만 바꾼다. {@code names}는 넣은 언어만 바꾸며 빈 값은
 * 안 되고, {@code cardColor: null}은 색을 지운다. {@code adminHidden}·{@code pinnedOnTab}은 null로 지울 수 없다.
 * slug와 부모는 바꿀 수 없어 필드가 없다.
 */
public class UpdateTopicRequest {

    private Map<String, String> names;
    private boolean namesPresent;
    private String cardColor;
    private boolean cardColorPresent;
    private Boolean adminHidden;
    private boolean adminHiddenPresent;
    private Boolean pinnedOnTab;
    private boolean pinnedOnTabPresent;

    public Map<String, String> getNames() {
        return names;
    }

    public void setNames(Map<String, String> names) {
        this.names = names;
        this.namesPresent = true;
    }

    public boolean hasNames() {
        return namesPresent;
    }

    public String getCardColor() {
        return cardColor;
    }

    public void setCardColor(String cardColor) {
        this.cardColor = cardColor;
        this.cardColorPresent = true;
    }

    public boolean hasCardColor() {
        return cardColorPresent;
    }

    public Boolean getAdminHidden() {
        return adminHidden;
    }

    public void setAdminHidden(Boolean adminHidden) {
        this.adminHidden = adminHidden;
        this.adminHiddenPresent = true;
    }

    public boolean hasAdminHidden() {
        return adminHiddenPresent;
    }

    public Boolean getPinnedOnTab() {
        return pinnedOnTab;
    }

    public void setPinnedOnTab(Boolean pinnedOnTab) {
        this.pinnedOnTab = pinnedOnTab;
        this.pinnedOnTabPresent = true;
    }

    public boolean hasPinnedOnTab() {
        return pinnedOnTabPresent;
    }
}
