package net.java21.blog.backend.media;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 이미지 설정(T213, research R11, contracts/api.md "프로퍼티"). 세 디렉터리는 필수이고 기동 때 만들고 쓰기 가능한지 확인한다
 * ({@code LocalMediaStorage}).
 *
 * @param uploadDir    정식 보관(ATTACHED·ORPHANED) 디렉터리. 파일은 {@code yyyy/MM/{uuid}.{ext}}
 * @param tempDir      임시 보관(TEMP) 디렉터리. 파일은 {@code {uuid}.{ext}}
 * @param thumbnailDir 썸네일 디렉터리(다시 만들 수 있어 백업 제외). 파일은 {@code {key}/{w}x{h}-{fit}.{ext}}
 * @param tempTtl      임시 보관 기간(FR-072). 지나면 정리 작업이 지운다
 * @param tempQuota    회원별 TEMP 합계 한도(FR-074)
 * @param maxSize      파일당 최대 크기(FR-038). {@code spring.servlet.multipart.max-file-size}와 같게 둔다
 * @param maxPixels    원본 가로×세로 상한. 작은 파일이 거대한 그림으로 풀리는 압축 폭탄을 막는다(넘으면 413)
 * @param cleanupCron  정리 작업 주기(기본 매시 정각)
 * @param allowedTypes 업로드 허용 형식(파일 내용으로 판별)
 * @param thumbnail    썸네일 허용 크기
 */
@ConfigurationProperties("blog.media")
public record MediaProperties(
        Path uploadDir,
        Path tempDir,
        Path thumbnailDir,
        @DefaultValue("24h") Duration tempTtl,
        @DefaultValue("200MB") DataSize tempQuota,
        @DefaultValue("10MB") DataSize maxSize,
        @DefaultValue("40000000") long maxPixels,
        @DefaultValue("0 0 * * * *") String cleanupCron,
        @DefaultValue({"image/jpeg", "image/png", "image/gif", "image/webp"}) List<String> allowedTypes,
        @DefaultValue Thumbnail thumbnail) {

    public MediaProperties {
        require(uploadDir, "upload-dir");
        require(tempDir, "temp-dir");
        require(thumbnailDir, "thumbnail-dir");
        if (tempTtl.isNegative() || tempTtl.isZero()) {
            throw new IllegalArgumentException("blog.media.temp-ttl must be positive");
        }
        if (maxSize.toBytes() <= 0 || tempQuota.toBytes() <= 0 || maxPixels <= 0) {
            throw new IllegalArgumentException("blog.media.max-size, temp-quota and max-pixels must be positive");
        }
        allowedTypes = List.copyOf(allowedTypes);
    }

    private static void require(Path dir, String name) {
        if (dir == null || dir.toString().isBlank()) {
            throw new IllegalArgumentException("blog.media." + name + " is required");
        }
    }

    /**
     * 썸네일 허용 크기(FR-131). 기본은 50x50, 100x100, 160x160, 300x200, 600x400, 1200x630과 각 2배(중복 없이).
     *
     * @param sizes {@code {가로}x{세로}} 목록
     */
    public record Thumbnail(
            @DefaultValue({"50x50", "100x100", "160x160", "200x200", "300x200", "320x320", "600x400", "1200x630",
                    "1200x800", "2400x1260"}) List<String> sizes) {

        private static final Pattern SIZE = Pattern.compile("([1-9][0-9]{0,3})x([1-9][0-9]{0,3})");

        public Thumbnail {
            sizes = List.copyOf(sizes);
            for (String size : sizes) {
                if (!SIZE.matcher(size).matches()) {
                    throw new IllegalArgumentException("Invalid blog.media.thumbnail.sizes entry: " + size);
                }
            }
        }

        /** 허용 목록을 {@code "300x200"} 형태의 집합으로. */
        public Set<String> allowed() {
            return sizes.stream().collect(Collectors.toUnmodifiableSet());
        }

        /** {@code "300x200"} → {300, 200}. 형식이 틀리면 null. */
        public static int[] parse(String size) {
            Matcher matcher = SIZE.matcher(size == null ? "" : size);
            if (!matcher.matches()) {
                return null;
            }
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
    }
}
