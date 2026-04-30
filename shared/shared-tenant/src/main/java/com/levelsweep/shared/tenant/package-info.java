/**
 * Multi-tenant infrastructure: TenantContext (request-scoped), JWT extraction filter,
 * and Kafka header propagation helpers.
 *
 * <p>Per architecture-spec §3 every persistent entity carries {@code tenant_id}; every
 * Kafka topic is keyed by tenant. Phase A operates as a single-tenant deployment with
 * {@code tenant_id = OWNER}, but the code path is identical to Phase B.
 *
 * <p>Phase 0 placeholder. Actual implementations are added in Phase 6.
 */
package com.levelsweep.shared.tenant;
