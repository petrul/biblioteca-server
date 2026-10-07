package ro.editii.scriptorium.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import ro.editii.scriptorium.service.AdminService;
import ro.editii.scriptorium.tei.TeiResourceNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TeiSourceMissingAdviceTest {
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/vnd.hal+json", "application/xml", "text/xml", "text/html", "text/plain"})
    void missingSourceReturns404ForEveryAcceptedContentType(String mediaType) throws Exception {
        AdminService admin = mock(AdminService.class);
        DivController controller = spy(new DivController(null, null, null, null, null, null, null, null, null));
        doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(2);
            response.setContentType(mediaType);
            throw new TeiResourceNotFoundException("/fr/missing.xml");
        }).when(controller).requestForTxt(anyString(), any(), any(), any(), isNull());

        standaloneSetup(controller).setControllerAdvice(new TeiSourceMissingAdvice(admin))
                .build().perform(get("/author/work/chapter.txt").accept(mediaType))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(admin).pruneMissingTeiOnRequest("/fr/missing.xml");
    }

    @Test
    void contentDispatcherLetsMissingSourceReachCleanupAdvice() throws Exception {
        AdminService admin = mock(AdminService.class);
        DivController controller = spy(new DivController(null, null, null, null, null, null, null, null, null));
        doThrow(new TeiResourceNotFoundException("/fr/missing.xml"))
                .when(controller).requestForTxt(anyString(), any(), any(), any(), isNull());

        standaloneSetup(controller).setControllerAdvice(new TeiSourceMissingAdvice(admin))
                .build().perform(get("/author/work/chapter.txt"))
                .andExpect(status().isNotFound());

        verify(admin).pruneMissingTeiOnRequest("/fr/missing.xml");
    }

    @Test
    void cleansUpTheExactSourceBeforeReturning404() {
        AdminService admin = mock(AdminService.class);
        var response = new TeiSourceMissingAdvice(admin)
                .sourceMissing(new TeiResourceNotFoundException("/fr/missing.xml"));

        verify(admin).pruneMissingTeiOnRequest("/fr/missing.xml");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void cleanupFailureStillReturns404() {
        AdminService admin = mock(AdminService.class);
        doThrow(new IllegalStateException("repository unavailable"))
                .when(admin).pruneMissingTeiOnRequest("/fr/missing.xml");

        var response = new TeiSourceMissingAdvice(admin)
                .sourceMissing(new TeiResourceNotFoundException("/fr/missing.xml"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
