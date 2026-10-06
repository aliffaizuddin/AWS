package dev.cloudlite.s3.reconcile;

import java.util.List;

public record ReconcileReport(int expired, int pruned, int blobsDeleted, List<String> failedSteps) {
}
