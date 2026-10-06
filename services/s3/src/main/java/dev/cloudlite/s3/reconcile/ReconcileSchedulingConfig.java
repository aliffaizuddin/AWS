package dev.cloudlite.s3.reconcile;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

// Configured programmatically so the interval binds as a Duration ("10m").
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "s3.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileSchedulingConfig implements SchedulingConfigurer {

    private final MultipartReconciler reconciler;
    private final Duration interval;

    public ReconcileSchedulingConfig(MultipartReconciler reconciler,
                                     @Value("${s3.reconcile.interval:10m}") Duration interval) {
        this.reconciler = reconciler;
        this.interval = interval;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(reconciler::runOnce, interval, interval));
    }
}
