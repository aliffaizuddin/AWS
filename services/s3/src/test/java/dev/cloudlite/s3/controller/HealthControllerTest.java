package dev.cloudlite.s3.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSource dataSource;

    @Test
    void healthzReturns200WhenTheDatabaseIsReachable() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        mockMvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void healthzReturns503WhenTheDatabaseIsUnreachable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        mockMvc.perform(get("/healthz")).andExpect(status().isServiceUnavailable());
    }
}
