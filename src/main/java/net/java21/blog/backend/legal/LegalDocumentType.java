package net.java21.blog.backend.legal;

/** 약관 문서 종류(FR-137). {@link #resourceName()}은 본문 리소스 파일 이름 앞부분({@code legal/{name}_{lang}.md}). */
public enum LegalDocumentType {
    TERMS("terms"),
    PRIVACY("privacy");

    private final String resourceName;

    LegalDocumentType(String resourceName) {
        this.resourceName = resourceName;
    }

    public String resourceName() {
        return resourceName;
    }
}
