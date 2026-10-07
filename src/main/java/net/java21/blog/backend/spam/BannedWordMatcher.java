package net.java21.blog.backend.spam;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.error.FieldErrorCode;
import net.java21.blog.backend.common.text.TextNormalizer;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.spam.repository.BannedWordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 금칙어 검사(005 FR-143, research M11). 전체 목록을 메모리에 두고(처음 쓸 때 읽고, 바뀌면 커밋 뒤 다시 읽음) 같은 정규화를 거친 입력에서
 * 부분 문자열 일치를 찾는다. 단어 수가 수백 개 규모라 단순 반복으로 충분하다. 쓰기 경로에서 쿼리 0회.
 * <ul>
 *   <li>이름류(NAME·ALL 단어): 닉네임·블로그 주소·블로그 제목·비회원 이름. 포함되면 400 {@code VALIDATION_FAILED} field
 *       {@code BANNED_WORD}. 공백·구두점을 지운 문자열에도 검사한다.</li>
 *   <li>본문류(CONTENT·ALL 단어): 댓글·방명록 내용. {@code REJECT} 단어가 하나라도 있으면 같은 400, {@code MASK} 단어만 있으면 원문에서 그
 *       부분을 같은 길이의 {@code *}로 바꾼 값을 돌려준다.</li>
 * </ul>
 * 어느 단어인지는 응답에 넣지 않는다.
 */
@Component
public class BannedWordMatcher {

    /** 캐시한 단어(정규화 값, 이름류 비교용 압축 값). */
    record Entry(String word, String compactWord, BannedWordScope scope, BannedWordAction action) {

        boolean names() {
            return scope == BannedWordScope.NAME || scope == BannedWordScope.ALL;
        }

        boolean content() {
            return scope == BannedWordScope.CONTENT || scope == BannedWordScope.ALL;
        }
    }

    private final BannedWordRepository repository;
    private volatile List<Entry> entries;

    public BannedWordMatcher(BannedWordRepository repository) {
        this.repository = repository;
    }

    /** 목록을 다시 읽는다. */
    public void reload() {
        entries = repository.findAll().stream()
                .map(w -> new Entry(w.getWord(), TextNormalizer.compact(w.getWord()), w.getScope(), w.getAction()))
                .toList();
    }

    /** 금칙어 추가·변경·삭제가 커밋된 뒤(트랜잭션 밖에서 발행하면 바로) 다시 읽는다. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onChanged(BannedWordsChangedEvent event) {
        reload();
    }

    private List<Entry> entries() {
        List<Entry> current = entries;
        if (current == null) {
            reload();
            current = entries;
        }
        return current;
    }

    /** 이름류 값에 금칙어가 있는지. */
    public boolean containsNameWord(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = normalizeForMatch(value).text();
        String compact = TextNormalizer.compact(value);
        for (Entry entry : entries()) {
            if (entry.names() && (normalized.contains(entry.word())
                    || (!entry.compactWord().isEmpty() && compact.contains(entry.compactWord())))) {
                return true;
            }
        }
        return false;
    }

    /** 이름류 값 검사. 금칙어가 있으면 400 {@code VALIDATION_FAILED}({@code field} {@code BANNED_WORD}). */
    public void requireCleanName(String field, String value) {
        if (containsNameWord(value)) {
            throw bannedWord(field);
        }
    }

    /** 이름류 검사 결과를 오류 목록에 더한다(여러 입력란을 한 번에 검증하는 곳). */
    public void collectName(List<FieldError> errors, String field, String value) {
        if (containsNameWord(value)) {
            errors.add(FieldError.of(field, FieldErrorCode.BANNED_WORD));
        }
    }

    /**
     * 본문류 값 검사. REJECT 단어가 있으면 400, MASK 단어만 있으면 가린 값, 없으면 그대로.
     */
    public String filterContent(String field, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        Normalized normalized = normalizeForMatch(value);
        List<String> masks = new ArrayList<>();
        for (Entry entry : entries()) {
            if (!entry.content() || entry.word().isEmpty() || !normalized.text().contains(entry.word())) {
                continue;
            }
            if (entry.action() == BannedWordAction.REJECT) {
                throw bannedWord(field);
            }
            masks.add(entry.word());
        }
        return masks.isEmpty() ? value : mask(value, normalized, masks);
    }

    /** 코드 포인트마다 정규화한 문자열과 각 글자의 원문 코드 포인트 위치(가림 위치 계산용). */
    record Normalized(String text, int[] codePoints, int[] owner) {
    }

    static Normalized normalizeForMatch(String value) {
        int[] cps = value.codePoints().toArray();
        StringBuilder text = new StringBuilder(cps.length);
        List<Integer> owners = new ArrayList<>(cps.length);
        for (int i = 0; i < cps.length; i++) {
            String piece = Normalizer.normalize(new String(Character.toChars(cps[i])), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            for (int k = 0; k < piece.length(); k++) {
                text.append(piece.charAt(k));
                owners.add(i);
            }
        }
        return new Normalized(text.toString(), cps, owners.stream().mapToInt(Integer::intValue).toArray());
    }

    static String mask(String value, Normalized normalized, List<String> words) {
        boolean[] masked = new boolean[normalized.codePoints().length];
        String text = normalized.text();
        for (String word : words) {
            int from = 0;
            int at;
            while ((at = text.indexOf(word, from)) >= 0) {
                for (int k = at; k < at + word.length(); k++) {
                    masked[normalized.owner()[k]] = true;
                }
                from = at + 1;
            }
        }
        StringBuilder out = new StringBuilder(value.length());
        int[] cps = normalized.codePoints();
        for (int i = 0; i < cps.length; i++) {
            if (masked[i]) {
                out.append('*');
            } else {
                out.appendCodePoint(cps[i]);
            }
        }
        return out.toString();
    }

    private static BusinessException bannedWord(String field) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Banned word in " + field,
                List.of(FieldError.of(field, FieldErrorCode.BANNED_WORD)));
    }
}
