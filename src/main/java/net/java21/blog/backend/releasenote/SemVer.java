package net.java21.blog.backend.releasenote;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 릴리스 노트 버전(006 data-model {@code release_notes.version}): SemVer 핵심 세 숫자 {@code MAJOR.MINOR.PATCH}, 앞자리 0 없음.
 * 정렬과 "가장 높은 버전"은 문자열이 아니라 세 숫자로 비교한다(1.10.0 > 1.9.0).
 */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    public static final Pattern FORMAT = Pattern.compile("^(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})$");

    private static final Comparator<SemVer> ORDER = Comparator.comparingInt(SemVer::major)
            .thenComparingInt(SemVer::minor).thenComparingInt(SemVer::patch);

    /** 형식이 맞으면 값, 아니면 빈 값(null 포함). 각 자리는 6자리까지(정수 범위 안). */
    public static Optional<SemVer> parse(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(version);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new SemVer(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    public boolean isNewerThan(SemVer other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(SemVer other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
