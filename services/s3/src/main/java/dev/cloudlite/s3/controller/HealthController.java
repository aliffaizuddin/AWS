package dev.cloudlite.s3.controller;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Void> healthz() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok().build();
            }
        } catch (Exception e) {
            // fall through to 503
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
