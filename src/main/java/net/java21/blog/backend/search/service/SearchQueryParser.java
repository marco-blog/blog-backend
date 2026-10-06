package net.java21.blog.backend.search.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.search.SearchProperties;
import org.springframework.stereotype.Component;

/**
 * 검색어 해석(002 research D4, contracts/api.md 검색 절).
 * <ol>
 *   <li>앞뒤 공백을 뺀 뒤 2~100자(문자 수). 없으면 {@code REQUIRED}, 짧으면 {@code TOO_SHORT}({@code min: 2}), 길면 {@code TOO_LONG}({@code max: 100}).</li>
 *   <li>공백으로 나눈 낱말에서 BOOLEAN MODE 연산자 문자({@code + - < > ( ) ~ * " @})를 지우고, {@code min-term-length}자 이상만
 *       앞에서부터 {@code max-terms}개(중복 제외)를 쓴다. 남는 낱말이 없으면 {@code TOO_SHORT}.</li>
 *   <li>각 낱말을 {@code +"낱말"}로 묶어 모두 포함하게 한다(낱말 안은 ngram 구문 일치).</li>
 * </ol>
 * 오류는 field {@code q}의 400 {@code VALIDATION_FAILED}.
 */
@Component
public class SearchQueryParser {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;
    private static final String FIELD = "q";

    private final SearchProperties properties;

    public SearchQueryParser(SearchProperties properties) {
        this.properties = properties;
    }

    public SearchQuery parse(String q) {
        String trimmed = q == null ? "" : q.strip();
        if (trimmed.isEmpty()) {
            throw invalid("REQUIRED", Map.of());
        }
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_LENGTH) {
            throw invalid("TOO_SHORT", Map.of("min", MIN_LENGTH));
        }
        if (length > MAX_LENGTH) {
            throw invalid("TOO_LONG", Map.of("max", MAX_LENGTH));
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : trimmed.split("\\s+")) {
            String term = stripOperators(raw);
            if (term.codePointCount(0, term.length()) >= properties.minTermLength()) {
                terms.add(term);
                if (terms.size() == properties.maxTerms()) {
                    break;
                }
            }
        }
        if (terms.isEmpty()) {
            throw invalid("TOO_SHORT", Map.of("min", MIN_LENGTH));
        }
        List<String> list = new ArrayList<>(terms);
        return new SearchQuery(list, list.stream().map(t -> "+\"" + t + "\"").collect(Collectors.joining(" ")));
    }

    /** MySQL BOOLEAN MODE 연산자 문자를 지운다. */
    static String stripOperators(String term) {
        return term.replaceAll("[+\\-<>()~*\"@]", "");
    }

    private static BusinessException invalid(String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid search query",
                List.of(new FieldError(FIELD, code, params)));
    }
}
