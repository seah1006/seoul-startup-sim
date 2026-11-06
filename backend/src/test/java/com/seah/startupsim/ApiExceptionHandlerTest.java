package com.seah.startupsim;

import com.seah.startupsim.common.ApiExceptionHandler;
import com.seah.startupsim.common.ExternalProperties;
import com.seah.startupsim.common.SecretMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    @RestController static class Probe {
        @GetMapping("/failure") public String fail() { throw new IllegalStateException("fixture-secret"); }
    }
    @Test @DisplayName("예상하지 못한 오류는 내부 원인을 노출하지 않고 500 공통 형식으로 반환한다")
    void unexpected() throws Exception {
        ExternalProperties properties = new ExternalProperties();
        properties.getSeoul().setApiKey("fixture-secret");
        MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new ApiExceptionHandler(new SecretMasker(properties))).build()
                .perform(get("/failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-secret"))));
    }
}
