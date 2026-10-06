package net.java21.blog.backend.legal.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.java21.blog.backend.content.MarkdownRenderer;
import net.java21.blog.backend.legal.LegalDocumentType;
import net.java21.blog.backend.legal.LegalProperties;
import net.java21.blog.backend.legal.dto.LegalDocumentResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 약관·개인정보처리방침 본문(T143, FR-137·155, contracts/api.md legal 절).
 * <ul>
 *   <li>본문은 리소스 {@code legal/{terms|privacy}_{lang}.md}를 글 본문과 같은 {@link MarkdownRenderer}로 변환·살균한다.
 *       파일은 배포물 안에서 바뀌지 않으므로 처음 읽을 때 한 번 변환해 둔다.</li>
 *   <li>요청 언어판이 없으면 en, en도 없으면 ko(기준 언어판). 지원하지 않거나 빈 {@code lang}도 같은 순서를 따른다.</li>
 *   <li>버전은 4개 언어 공통 하나({@code blog.legal.terms-version}). 시행일은 버전이 날짜일 때 그 날짜다.</li>
 * </ul>
 */
@Service
public class LegalService {

    /** 지원 언어(화면 언어와 같다). */
    public static final List<String> LANGUAGES = List.of("ko", "en", "ja", "zh-CN");
    static final String AUTHORITATIVE_LANG = "ko";
    private static final String FALLBACK_LANG = "en";
    private static final String DEFAULT_LOCATION = "legal/";

    private final LegalProperties legalProperties;
    private final MarkdownRenderer renderer;
    private final String location;
    private final Map<String, Optional<String>> htmlCache = new ConcurrentHashMap<>();

    @Autowired
    public LegalService(LegalProperties legalProperties, MarkdownRenderer renderer) {
        this(legalProperties, renderer, DEFAULT_LOCATION);
    }

    /** 테스트용: 본문 리소스 위치(클래스패스, {@code /}로 끝남)를 바꾼다. */
    LegalService(LegalProperties legalProperties, MarkdownRenderer renderer, String location) {
        this.legalProperties = legalProperties;
        this.renderer = renderer;
        this.location = location;
    }

    public LegalDocumentResponse document(LegalDocumentType type, String requestedLang) {
        for (String lang : candidates(requestedLang)) {
            Optional<String> html = htmlCache.computeIfAbsent(type.resourceName() + "_" + lang, this::load);
            if (html.isPresent()) {
                String version = legalProperties.termsVersion();
                return new LegalDocumentResponse(version, lang, AUTHORITATIVE_LANG, effectiveAt(version), html.get());
            }
        }
        throw new IllegalStateException("legal document not found: " + location + type.resourceName() + "_ko.md");
    }

    private static Set<String> candidates(String requestedLang) {
        Set<String> candidates = new LinkedHashSet<>();
        if (requestedLang != null && LANGUAGES.contains(requestedLang)) {
            candidates.add(requestedLang);
        }
        candidates.add(FALLBACK_LANG);
        candidates.add(AUTHORITATIVE_LANG);
        return candidates;
    }

    private Optional<String> load(String name) {
        ClassPathResource resource = new ClassPathResource(location + name + ".md");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(renderer.render(new String(in.readAllBytes(), StandardCharsets.UTF_8)).html());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource.getPath(), e);
        }
    }

    private static LocalDate effectiveAt(String version) {
        try {
            return LocalDate.parse(version);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
