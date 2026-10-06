package net.java21.blog.backend.common;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CommonResponseTestController.class)
@Import({WebMvcTestSupport.class, CommonResponseTestController.class})
class CommonResponseWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Nested
    @WithMockUser
    class Success {

        @Test
        void wrapsSingleResource() throws Exception {
            mvc.perform(get("/api/v1/test/items/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.isSuccessful").value(true))
                    .andExpect(jsonPath("$.header.resultCode").value("OK"))
                    .andExpect(jsonPath("$.header.resultMessage").value(""))
                    .andExpect(jsonPath("$.header.fieldErrors").doesNotExist())
                    .andExpect(jsonPath("$.header.traceId").doesNotExist())
                    .andExpect(jsonPath("$.result.id").value(7))
                    .andExpect(jsonPath("$.totalCount").doesNotExist())
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        @Test
        void pageHasResultArrayAndTotalCount() throws Exception {
            mvc.perform(get("/api/v1/test/items").param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.length()").value(2))
                    .andExpect(jsonPath("$.totalCount").value(135));
        }

        @Test
        void pageRequestsClampsSizeAndAppliesSort() throws Exception {
            mvc.perform(get("/api/v1/test/paged").param("page", "1").param("size", "500").param("sort", "title,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result[0].title").value("page=1 size=50 sort=title: ASC"))
                    .andExpect(jsonPath("$.totalCount").value(135));
            mvc.perform(get("/api/v1/test/paged"))
                    .andExpect(jsonPath("$.result[0].title").value("page=0 size=20 sort=id: DESC"));
        }

        @Test
        void cursorListHasNextCursorOnlyWhenMore() throws Exception {
            mvc.perform(get("/api/v1/test/feed"))
                    .andExpect(jsonPath("$.nextCursor").value("next-1"));
            mvc.perform(get("/api/v1/test/feed").param("cursor", "next-1"))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        @Test
        void createdReturns201() throws Exception {
            mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"abc\",\"count\":3}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.result.title").value("abc"));
        }

        @Test
        void emptySuccessIs200WithNullResult() throws Exception {
            mvc.perform(delete("/api/v1/test/items/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.isSuccessful").value(true))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }

        @Test
        void issuesRequestIdAndReusesValidIncomingOne() throws Exception {
            mvc.perform(get("/api/v1/test/items/1"))
                    .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{16}")));
            mvc.perform(get("/api/v1/test/items/1").header("X-Request-Id", "front-1234abcd"))
                    .andExpect(header().string("X-Request-Id", "front-1234abcd"));
            mvc.perform(get("/api/v1/test/items/1").header("X-Request-Id", "bad id!"))
                    .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{16}")));
        }
    }

    @Nested
    @WithMockUser
    class Failure {

        @Test
        void businessExceptionUsesItsStatusAndCode() throws Exception {
            mvc.perform(get("/api/v1/test/items/404").header("X-Request-Id", "trace-12345678"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.isSuccessful").value(false))
                    .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.header.resultMessage").value("Item not found: 404"))
                    .andExpect(jsonPath("$.header.traceId").value("trace-12345678"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }

        @Test
        void businessExceptionCanCarryFieldErrors() throws Exception {
            mvc.perform(post("/api/v1/test/rule"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.fieldErrors[0].field").value("handle"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].code").value("HANDLE_RESERVED"));
        }

        @Test
        void bodyValidationListsFieldErrorsWithParams() throws Exception {
            mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"abcdef\",\"count\":0,\"email\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.header.fieldErrors[?(@.field=='title')].code").value("TOO_LONG"))
                    .andExpect(jsonPath("$.header.fieldErrors[?(@.field=='title')].params.max").value(5))
                    .andExpect(jsonPath("$.header.fieldErrors[?(@.field=='count')].code").value("TOO_SMALL"))
                    .andExpect(jsonPath("$.header.fieldErrors[?(@.field=='email')].code").value("INVALID_FORMAT"));
        }

        @Test
        void tooShortAndRequired() throws Exception {
            mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"a\",\"count\":3}"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_SHORT"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].params.min").value(2));
            mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\",\"count\":3}"))
                    .andExpect(jsonPath("$.header.fieldErrors[?(@.code=='REQUIRED')].field").value("title"));
        }

        @Test
        void malformedBody() throws Exception {
            mvc.perform(post("/api/v1/test/items").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
        }

        @Test
        void missingAndMistypedParameters() throws Exception {
            mvc.perform(get("/api/v1/test/items"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.fieldErrors[0].field").value("page"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
            mvc.perform(get("/api/v1/test/items").param("page", "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID_FORMAT"));
        }

        @Test
        void pageRequestsRejectsDisallowedSort() throws Exception {
            mvc.perform(get("/api/v1/test/paged").param("sort", "password,desc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].field").value("sort"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }

        @Test
        void parameterConstraint() throws Exception {
            mvc.perform(get("/api/v1/test/items").param("page", "0").param("size", "51"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.fieldErrors[0].field").value("size"))
                    .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_LARGE"));
        }

        @Test
        void unknownPathMethodAndMediaType() throws Exception {
            mvc.perform(get("/api/v1/test/nothing"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("NOT_FOUND"));
            mvc.perform(put("/api/v1/test/items/1"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.header.resultCode").value("METHOD_NOT_ALLOWED"));
            mvc.perform(post("/api/v1/test/items").contentType(MediaType.TEXT_PLAIN).content("x"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.header.resultCode").value("UNSUPPORTED_MEDIA_TYPE"));
        }

        @Test
        void unexpectedErrorHidesDetails() throws Exception {
            mvc.perform(get("/api/v1/test/items/500"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.header.resultCode").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.header.resultMessage").value("Internal error"));
        }
    }

    @Test
    void unauthenticatedRequestGets401InCommonFormat() throws Exception {
        mvc.perform(get("/api/v1/test/items/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.header.traceId").exists())
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
