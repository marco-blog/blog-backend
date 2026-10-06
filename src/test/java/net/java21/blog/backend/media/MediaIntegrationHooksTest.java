package net.java21.blog.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import net.java21.blog.backend.media.job.MediaCleanupJob;
import net.java21.blog.backend.post.job.TrashPurgeJob;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 이미지 연결 흐름(T207, FR-071~074, FR-107, FR-156, quickstart #13·14·24·25). 전체 컨텍스트를 H2와 {@code @TempDir} 디렉터리로 띄운다.
 * 업로드(TEMP) → 남에게 404 → 임시저장으로 등록(upload-dir 이동, 주소 그대로) → 발행 대표 이미지 → 사본에서만 뺀 이미지는 유지 →
 * 수정 발행 뒤 정리 대상 → 썸네일 → 프로필·블로그 대표 이미지 → 휴지통 영구 삭제 뒤 판단 → 정리 작업.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediaflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=VALUE,USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MediaIntegrationHooksTest {

    private static final String ORIGIN = "http://localhost:5173";

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void mediaDirs(DynamicPropertyRegistry registry) {
        registry.add("blog.media.upload-dir", () -> root.resolve("upload").toString());
        registry.add("blog.media.temp-dir", () -> root.resolve("temp").toString());
        registry.add("blog.media.thumbnail-dir", () -> root.resolve("thumb").toString());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TrashPurgeJob trashPurgeJob;
    @Autowired
    private MediaCleanupJob cleanupJob;

    @org.junit.jupiter.api.BeforeEach
    void otherFeatureTables() {
        // 트랙백(005) 엔티티는 아직 없어 H2에 테이블이 없다. 영구 삭제가 지우는 테이블만 같은 이름으로 둔다.
        jdbc.execute("CREATE TABLE IF NOT EXISTS trackbacks (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " post_id BIGINT NOT NULL, source_post_id BIGINT, source_url VARCHAR(1000) NOT NULL)");
    }

    @Test
    void imagesAreRegisteredByPostsProfileAndCoverAndCleanedUpWhenUnused() throws Exception {
        Cookie marco = signup("marco@example.com", "marco");
        Cookie other = signup("other@example.com", "other");

        // 업로드: TEMP, 올린 회원에게만
        String a = upload(marco, TestImages.png(800, 600), "POST");
        String b = upload(marco, TestImages.jpeg(640, 480), "POST");
        assertThat(Files.list(root.resolve("temp")).count()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/media/" + a)).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + a).cookie(other)).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + a).cookie(marco)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));

        // 남의 이미지를 본문에 넣어도 연결하지 않는다(남의 TEMP는 그대로).
        json(post("/api/v1/blogs/other/posts/drafts"), "{\"title\":\"남\",\"contentMarkdown\":\"![x](/media/" + a + ")\"}",
                other).andExpect(status().isCreated());
        assertThat(mediaStatus(a)).isEqualTo("TEMP");

        // 임시저장: 등록(ATTACHED)·upload-dir로 이동, 주소는 그대로 누구나
        MvcResult created = json(post("/api/v1/blogs/marco/posts/drafts"),
                "{\"title\":\"그림 글\",\"contentMarkdown\":\"![a](/media/" + a + ")\\n\\n![b](/media/" + b + ")\"}",
                marco).andExpect(status().isCreated()).andReturn();
        long postId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.result.id")).longValue();
        assertThat(mediaStatus(a)).isEqualTo("ATTACHED");
        assertThat(jdbc.queryForObject("SELECT stored_path FROM media WHERE media_key = ?", String.class, a))
                .matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        mvc.perform(get("/media/" + a)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));

        // 발행: 대표 이미지는 고른 본문 이미지, 생략하면 첫 이미지
        json(post("/api/v1/posts/" + postId + "/publish"), "{\"visibility\":\"PUBLIC\",\"thumbnailMediaKey\":\"" + b + "\"}",
                marco).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.thumbnailUrl").value("/media/" + b))
                .andExpect(jsonPath("$.result.author.profileImageUrl").doesNotExist());

        // 작성 중 사본에서만 a를 빼면 발행본이 쓰므로 유지
        json(put("/api/v1/posts/" + postId + "/draft"), "{\"title\":\"그림 글\",\"contentMarkdown\":\"![b](/media/" + b + ")\"}",
                marco).andExpect(status().isOk());
        assertThat(mediaStatus(a)).isEqualTo("ATTACHED");
        // 수정 발행하면 a는 어디서도 쓰지 않으므로 정리 대상
        json(post("/api/v1/posts/" + postId + "/publish"), "{\"visibility\":\"PUBLIC\"}", marco)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.thumbnailUrl").value("/media/" + b));
        assertThat(mediaStatus(a)).isEqualTo("ORPHANED");
        mvc.perform(get("/media/" + a)).andExpect(status().isOk());

        // 썸네일: 허용 크기만, 처음 요청 때 만들어 저장
        mvc.perform(get("/media/" + b + "/300x200")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
        assertThat(root.resolve("thumb").resolve(b).resolve("300x200-cover.jpg")).exists();
        mvc.perform(get("/media/" + b + "/301x200")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("THUMBNAIL_SIZE_NOT_ALLOWED"));

        // 프로필 이미지: PROFILE로 올린 본인 이미지만, 바꾸면 이전 이미지는 정리 대상
        String postPurpose = upload(marco, TestImages.png(10, 10), "POST");
        json(patch("/api/v1/me"), "{\"profileImageMediaKey\":\"" + postPurpose + "\"}", marco)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("profileImageMediaKey"));
        String profile1 = upload(marco, TestImages.png(100, 100), "PROFILE");
        String othersProfile = upload(other, TestImages.png(100, 100), "PROFILE");
        json(patch("/api/v1/me"), "{\"profileImageMediaKey\":\"" + othersProfile + "\"}", marco)
                .andExpect(status().isBadRequest());
        json(patch("/api/v1/me"), "{\"profileImageMediaKey\":\"" + profile1 + "\"}", marco)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.profileImageUrl").value("/media/" + profile1));
        String profile2 = upload(marco, TestImages.png(100, 100), "PROFILE");
        json(patch("/api/v1/me"), "{\"profileImageMediaKey\":\"" + profile2 + "\"}", marco)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.profileImageUrl").value("/media/" + profile2));
        assertThat(mediaStatus(profile1)).isEqualTo("ORPHANED");
        assertThat(mediaStatus(profile2)).isEqualTo("ATTACHED");
        mvc.perform(get("/api/v1/posts/" + postId))
                .andExpect(jsonPath("$.result.author.profileImageUrl").value("/media/" + profile2));
        // 같은 이미지를 다시 저장해도 그대로
        json(patch("/api/v1/me"), "{\"profileImageMediaKey\":\"" + profile2 + "\"}", marco).andExpect(status().isOk());
        assertThat(mediaStatus(profile2)).isEqualTo("ATTACHED");

        // 블로그 대표 이미지
        String cover = upload(marco, TestImages.png(1200, 630), "BLOG_COVER");
        json(patch("/api/v1/blogs/marco"), "{\"coverImageMediaKey\":\"" + profile2 + "\"}", marco)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("coverImageMediaKey"));
        json(patch("/api/v1/blogs/marco"), "{\"coverImageMediaKey\":\"" + cover + "\"}", marco)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.coverImageUrl").value("/media/" + cover))
                .andExpect(jsonPath("$.result.owner.profileImageUrl").value("/media/" + profile2));
        mvc.perform(get("/api/v1/me/blogs").cookie(marco))
                .andExpect(jsonPath("$.result.items[0].coverImageUrl").value("/media/" + cover));
        json(patch("/api/v1/blogs/marco"), "{\"coverImageMediaKey\":null}", marco)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.coverImageUrl").doesNotExist());
        assertThat(mediaStatus(cover)).isEqualTo("ORPHANED");
        json(patch("/api/v1/me"), "{\"profileImageMediaKey\":null}", marco)
                .andExpect(jsonPath("$.result.profileImageUrl").doesNotExist());
        assertThat(mediaStatus(profile2)).isEqualTo("ORPHANED");

        // 휴지통 30일 뒤 영구 삭제: post_media를 지우고 판단 → b도 정리 대상
        mvc.perform(delete("/api/v1/posts/" + postId).header("Origin", ORIGIN).cookie(marco))
                .andExpect(status().isOk());
        assertThat(mediaStatus(b)).isEqualTo("ATTACHED");
        jdbc.update("UPDATE posts SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                postId);
        trashPurgeJob.purge();
        assertThat(mediaStatus(b)).isEqualTo("ORPHANED");

        // 정리 작업: ORPHANED 파일·썸네일·행 삭제 → 404. 아직 만료 전인 TEMP는 남는다.
        MediaCleanupJob.Result result = cleanupJob.cleanup();
        assertThat(result.orphaned()).isEqualTo(5);
        assertThat(result.temp()).isZero();
        mvc.perform(get("/media/" + b)).andExpect(status().isNotFound());
        assertThat(root.resolve("thumb").resolve(b)).doesNotExist();
        assertThat(mediaStatus(postPurpose)).isEqualTo("TEMP");
    }

    @Test
    void uploadRejectsFakeImagesAndAnonymous() throws Exception {
        Cookie user = signup("upload@example.com", "upload");
        mvc.perform(multipart("/api/v1/media")
                        .file(new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not an image".getBytes()))
                        .header("Origin", ORIGIN).cookie(user))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.header.resultCode").value("MEDIA_TYPE_NOT_ALLOWED"));
        mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file", TestImages.png(2, 2)))
                        .header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    private String mediaStatus(String key) {
        return jdbc.queryForObject("SELECT status FROM media WHERE media_key = ?", String.class, key);
    }

    private String upload(Cookie cookie, byte[] bytes, String purpose) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/media")
                        .file(new MockMultipartFile("file", "image", "application/octet-stream", bytes))
                        .param("purpose", purpose).header("Origin", ORIGIN).cookie(cookie))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.result.key");
    }

    private Cookie signup(String email, String handle) throws Exception {
        MvcResult signup = json(post("/api/v1/auth/signup"), """
                {"email":"%s","password":"password123","nickname":"%s","handle":"%s",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06"}"""
                .formatted(email, handle, handle), null)
                .andExpect(status().isCreated())
                .andReturn();
        return signup.getResponse().getCookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE);
    }

    private org.springframework.test.web.servlet.ResultActions json(MockHttpServletRequestBuilder builder, String body,
            Cookie cookie) throws Exception {
        builder.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            builder.cookie(cookie);
        }
        return mvc.perform(builder);
    }
}
