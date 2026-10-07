package net.java21.blog.backend.trackback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.trackback.service.PingForm;
import net.java21.blog.backend.trackback.service.ReceiveOutcome;
import net.java21.blog.backend.trackback.service.TrackbackReceiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 트랙백 받기 엔드포인트(005 T085, contracts/api.md "트랙백 받기", research M13). */
@WebMvcTest(TrackbackXmlController.class)
@Import(WebMvcTestSupport.class)
class TrackbackXmlControllerTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TrackbackReceiveService receiveService;

    @Test
    void acceptedPingAnswersErrorZeroAsUtf8Xml() throws Exception {
        when(receiveService.receive(eq("marco"), eq(42L), any(), eq("203.0.113.9")))
                .thenReturn(ReceiveOutcome.ACCEPTED);

        MvcResult result = mvc.perform(post("/marco/42/trackback")
                        .with(r -> {
                            r.setRemoteAddr("203.0.113.9");
                            return r;
                        })
                        .contentType("application/x-www-form-urlencoded; charset=utf-8")
                        .content("url=https%3A%2F%2Fother.example%2Fp%2F1&title=%ED%95%9C%EA%B8%80+%EC%A0%9C%EB%AA%A9"
                                + "&excerpt=a%26b&blog_name=Other"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(new MediaType("text", "xml", StandardCharsets.UTF_8)))
                .andExpect(content().string("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<response><error>0</error></response>"))
                .andReturn();
        assertThat(result.getResponse().getContentType()).containsIgnoringCase("charset=utf-8");

        ArgumentCaptor<PingForm> form = ArgumentCaptor.forClass(PingForm.class);
        verify(receiveService).receive(eq("marco"), eq(42L), form.capture(), eq("203.0.113.9"));
        assertThat(form.getValue()).isEqualTo(new PingForm("https://other.example/p/1", "한글 제목", "a&b", "Other"));
    }

    @ParameterizedTest
    @EnumSource(value = ReceiveOutcome.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void failuresAreHttp200WithErrorOneAndMessage(ReceiveOutcome outcome) throws Exception {
        when(receiveService.receive(anyString(), anyLong(), any(), any())).thenReturn(outcome);

        mvc.perform(post("/marco/42/trackback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("url=x"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/xml"))
                .andExpect(content().string("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<response><error>1</error>"
                        + "<message>" + outcome.message() + "</message></response>"));
    }

    @Test
    void decodesBodyBytesWithTheContentTypeCharset() throws Exception {
        when(receiveService.receive(anyString(), anyLong(), any(), any())).thenReturn(ReceiveOutcome.ACCEPTED);
        String body = "url=" + URLEncoder.encode("http://old.example/글/1", EUC_KR)
                + "&title=" + URLEncoder.encode("옛날 블로그 제목", EUC_KR)
                + "&blog_name=" + URLEncoder.encode("설치형", EUC_KR);

        mvc.perform(post("/marco/42/trackback").contentType("application/x-www-form-urlencoded; charset=EUC-KR")
                        .content(body.getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isOk());

        ArgumentCaptor<PingForm> form = ArgumentCaptor.forClass(PingForm.class);
        verify(receiveService).receive(eq("marco"), eq(42L), form.capture(), any());
        assertThat(form.getValue()).isEqualTo(new PingForm("http://old.example/글/1", "옛날 블로그 제목", null, "설치형"));
    }

    @Test
    void withoutCharsetTheBodyIsUtf8EvenForRawBytes() throws Exception {
        when(receiveService.receive(anyString(), anyLong(), any(), any())).thenReturn(ReceiveOutcome.ACCEPTED);

        mvc.perform(post("/marco/42/trackback").contentType("application/x-www-form-urlencoded")
                        .content("url=http://a.example/&title=날것 제목%zz&excerpt=100%".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        mvc.perform(post("/marco/42/trackback").contentType("application/x-www-form-urlencoded; charset=nope")
                        .content("url=http://b.example/".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());

        ArgumentCaptor<PingForm> form = ArgumentCaptor.forClass(PingForm.class);
        verify(receiveService, org.mockito.Mockito.times(2)).receive(eq("marco"), eq(42L), form.capture(), any());
        assertThat(form.getAllValues().get(0)).isEqualTo(new PingForm("http://a.example/", "날것 제목%zz", "100%", null));
        assertThat(form.getAllValues().get(1).url()).isEqualTo("http://b.example/");
    }

    @Test
    void queryStringFillsMissingValuesAndFirstValueWins() throws Exception {
        when(receiveService.receive(anyString(), anyLong(), any(), any())).thenReturn(ReceiveOutcome.ACCEPTED);

        mvc.perform(post("/marco/42/trackback?url=http%3A%2F%2Fq.example%2F&title=query")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("title=body&title=second&URL="))
                .andExpect(status().isOk());

        ArgumentCaptor<PingForm> form = ArgumentCaptor.forClass(PingForm.class);
        verify(receiveService).receive(eq("marco"), eq(42L), form.capture(), any());
        assertThat(form.getValue().title()).isEqualTo("body");
        assertThat(form.getValue().url()).as("본문의 빈 값이 먼저").isEmpty();
    }

    @Test
    void passesWithoutOrWithForeignOriginAndAnonymous() throws Exception {
        when(receiveService.receive(anyString(), anyLong(), any(), any())).thenReturn(ReceiveOutcome.ACCEPTED);

        mvc.perform(post("/marco/42/trackback").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("url=http://a.example/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<error>0</error>")));
    }

    @Test
    void nonNumericPostIdIs404AndOtherMethodsAre405() throws Exception {
        mvc.perform(post("/marco/abc/trackback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("url=http://a.example/"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/marco/42/trackback")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(receiveService);
    }

    @Test
    void responseIsNotTheCommonEnvelope() throws Exception {
        when(receiveService.receive(anyString(), anyLong(), any(), any())).thenReturn(ReceiveOutcome.NOT_ALLOWED);

        String body = mvc.perform(post("/marco/42/trackback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("url=http://a.example/"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("resultCode").startsWith("<?xml");
    }

    @Test
    void charsetHelper() {
        assertThat(TrackbackXmlController.charset(null)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TrackbackXmlController.charset("  ")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TrackbackXmlController.charset("garbage")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TrackbackXmlController.charset("application/x-www-form-urlencoded; charset=\"euc-kr\""))
                .isEqualTo(EUC_KR);
        assertThat(TrackbackXmlController.parseForm("a&=b&&c=1", StandardCharsets.UTF_8))
                .containsEntry("a", "").containsEntry("", "b").containsEntry("c", "1");
    }
}
