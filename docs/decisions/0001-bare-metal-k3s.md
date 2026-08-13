# 1. Bare-metal k3s, no Proxmox

## Status
Accepted

## Context
CloudLite needs a Kubernetes cluster to run on, on a single Ryzen 5 3500U
laptop (4 cores/8 threads, 12GB RAM, 256GB SSD + 1TB HDD). A common way to
simulate a "cluster" on one physical box is to carve it into VMs with
Proxmox and run k3s across them.

## Decision
Run k3s directly on the bare-metal host. No hypervisor layer.

## Consequences
- Every GB of RAM and CPU cycle goes to the workloads this project is
  actually about (S3/IAM/fnrunner, Postgres, PLG stack), not to VM
  overhead.
- One fewer layer to debug — an issue is either app-level or k3s-level,
  never "is this the VM or the app."
- No real node-level fault isolation is possible (there's only one
  physical machine either way), so this is an honest single-node setup,
  not a multi-node cluster wearing a costume.

## Alternatives considered
- **Proxmox + multiple VMs running k3s nodes**: rejected. VMs cost
  2-3GB RAM in overhead before any workload starts, and multi-VM
  "multi-node" on one box is partly theater — real node-level fault
  isolation needs real separate hardware. See `future-work.md` under
  "Multi-node k3s" for the actual trigger to revisit this (a second
  physical machine).
