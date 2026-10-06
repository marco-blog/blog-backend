package net.java21.blog.backend.common.persistence;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberTemplate;
import com.querydsl.core.types.dsl.StringExpression;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;

/**
 * MySQL FULLTEXT 검색 함수를 HQL 함수로 등록한다(002 research D4, plan Complexity Tracking).
 * 검색 쿼리를 QueryDSL로 쓰면서 노출 조건을 {@code PostExposure} 한 곳에 두기 위해서다.
 * <ul>
 *   <li>{@code match_title_content(title, contentText, q)} → {@code MATCH(title, content_text) AGAINST (q IN BOOLEAN MODE)}
 *       (인덱스 {@code ft_posts_title_content})</li>
 *   <li>{@code match_title(title, q)} → {@code MATCH(title) AGAINST (q IN BOOLEAN MODE)}(인덱스 {@code ft_posts_title})</li>
 *   <li>{@code match_tag(name, q)} → {@code MATCH(name) AGAINST (q IN BOOLEAN MODE)}(인덱스 {@code ft_tags_name})</li>
 * </ul>
 * 반환은 관련도(Double)이며 일치하면 0보다 크다. MySQL 전용이라 H2 테스트에서는 실행하지 않는다(등록만 된다).
 * 등록은 {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}로 한다.
 */
public class MySqlFullTextFunctions implements FunctionContributor {

    public static final String MATCH_TITLE_CONTENT = "match_title_content";
    public static final String MATCH_TITLE = "match_title";
    public static final String MATCH_TAG = "match_tag";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        BasicType<Double> doubleType = contributions.getTypeConfiguration().getBasicTypeRegistry()
                .resolve(StandardBasicTypes.DOUBLE);
        registry.registerPattern(MATCH_TITLE_CONTENT, "match(?1, ?2) against (?3 in boolean mode)", doubleType);
        registry.registerPattern(MATCH_TITLE, "match(?1) against (?2 in boolean mode)", doubleType);
        registry.registerPattern(MATCH_TAG, "match(?1) against (?2 in boolean mode)", doubleType);
    }

    /** QueryDSL 식: 글 제목+본문 일치 관련도. {@code .gt(0.0)}으로 거른다. */
    public static NumberTemplate<Double> matchTitleContent(StringExpression title, StringExpression contentText,
            String booleanQuery) {
        return Expressions.numberTemplate(Double.class, MATCH_TITLE_CONTENT + "({0}, {1}, {2})", title, contentText,
                Expressions.constant(booleanQuery));
    }

    /** QueryDSL 식: 글 제목 일치 관련도. */
    public static NumberTemplate<Double> matchTitle(StringExpression title, String booleanQuery) {
        return Expressions.numberTemplate(Double.class, MATCH_TITLE + "({0}, {1})", title,
                Expressions.constant(booleanQuery));
    }

    /** QueryDSL 식: 태그 이름 일치 관련도. */
    public static NumberTemplate<Double> matchTag(StringExpression name, String booleanQuery) {
        return Expressions.numberTemplate(Double.class, MATCH_TAG + "({0}, {1})", name,
                Expressions.constant(booleanQuery));
    }
}
