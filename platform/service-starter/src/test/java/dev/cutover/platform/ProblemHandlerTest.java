package dev.cutover.platform;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProblemHandlerTest {
    @RestController static class UnavailableController {
        @GetMapping("/unavailable/{kind}") String unavailable(@PathVariable String kind) {
            String privateDetail="synthetic-private-connection-detail";
            throw switch(kind) {
                case "transaction" -> new org.springframework.transaction.CannotCreateTransactionException(privateDetail);
                case "spring" -> new org.springframework.dao.DataAccessResourceFailureException(privateDetail);
                default -> new org.jooq.exception.DataAccessException(privateDetail);
            };
        }
    }

    @ParameterizedTest @ValueSource(strings={"transaction","spring","jooq"})
    void everyDatabaseBoundaryReturnsAUsefulUnavailableProblemWithoutInternalDetails(String kind) throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableController()).setControllerAdvice(new ProblemHandler()).build();
        var result=mvc.perform(get("/unavailable/"+kind))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After","2"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic-private-connection-detail");
    }
}
