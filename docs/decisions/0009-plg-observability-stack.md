# 9. Prometheus + Grafana + Loki (PLG), not ELK

## Status
Accepted

## Context
The platform needs metrics and log aggregation across S3, IAM, and the
function runner pods. The two realistic candidate stacks are PLG
(Prometheus/Grafana/Loki) and ELK (Elasticsearch/Logstash/Kibana). The
full hardware budget is 12GB RAM shared with every other workload.

## Decision
Use the PLG stack: Prometheus for metrics, Grafana for dashboards, Loki
for log aggregation.

## Consequences
- Combined footprint is roughly 1–1.5GB, versus ELK's realistic minimum
  of ~5–7GB (mostly Elasticsearch's JVM heap) — see `architecture.md`
  §13 for the full sizing breakdown.
- ELK at 5-7GB would consume 50-70% of the entire pod budget on this
  hardware, leaving little room for the actual services being built.
- PLG is also the more standard choice in k8s-native shops specifically,
  so this isn't purely a resource-constrained compromise.
- This is a documented trade-off: if later targeting a job posting that
  explicitly lists ELK, `future-work.md` names the swap-in path (Filebeat
  + Elasticsearch + Kibana replacing Loki) and `architecture.md` §13
  gives the hardware tier that would make it viable.

## Alternatives considered
- **Full ELK stack**: rejected for this hardware tier — see
  Consequences above. Not rejected in principle; explicitly named as a
  future-work revisit trigger.
- **Metrics only, no log aggregation**: rejected — the SRE interview
  narrative depends on being able to show logs during an incident, not
  just metrics.
