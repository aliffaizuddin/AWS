package dev.cloudlite.iam.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    @Test
    void explicitAllowGrantsAccess() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::my-bucket/report.csv")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:GetObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void explicitDenyWithNoAllowRejectsAccess() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.DENY, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::my-bucket/report.csv")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:DeleteObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void denyOverridesAllowOnConflictingStatements() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::my-bucket/*")),
            new PolicyStatement(Effect.DENY, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::my-bucket/*")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:DeleteObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void wildcardActionMatches() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:*"), List.of("arn:cloudlite:s3:::my-bucket/report.csv")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:PutObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void wildcardResourceMatches() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::my-bucket/*")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:GetObject", "arn:cloudlite:s3:::my-bucket/nested/report.csv");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void noMatchingStatementIsImplicitlyDenied() {
        List<PolicyStatement> statements = List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::other-bucket/*")));

        Decision decision = PolicyEngine.evaluate(statements, "s3:GetObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void emptyStatementListIsImplicitlyDenied() {
        Decision decision = PolicyEngine.evaluate(List.of(), "s3:GetObject", "arn:cloudlite:s3:::my-bucket/report.csv");

        assertThat(decision).isEqualTo(Decision.DENY);
    }
}
