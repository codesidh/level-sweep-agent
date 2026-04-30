---
name: multi-tenant-readiness
description: Rules for tenant isolation. Use when designing or reviewing schema, Kafka topics, services, tools, tests. Triggers on tenant, tenant_id, multi-tenant, tenant isolation, tenant scope, partition key, per-tenant.
---

# Multi-Tenant Readiness

Phase A is single-tenant operation. Phase B unlocks multi-tenant. **Code is multi-tenant-shaped from day 1.**

## Rules

1. **Every persistent entity has `tenant_id` (NOT NULL, INDEXED).** Every table, every collection. No exceptions.
2. **Every query includes `tenant_id`** in `WHERE` / filter. Use repository helpers that enforce this.
3. **Every Kafka topic is keyed by `tenant_id`** (or `(tenant_id, symbol)` where symbol partitioning matters).
4. **`TenantContext` is request-scoped**, populated from JWT claim at gateway, propagated via `X-Tenant-Id` header and Kafka message header.
5. **Tools / repositories accept `tenant_id` implicitly from context.** Never as a free parameter that callers can spoof.
6. **Tests verify tenant isolation**: write a test that creates two tenants, has tenant A write data, asserts tenant B cannot read it.
7. **No global mutable state.** Caches, in-memory state, FSM checkpoints — all keyed by tenant.
8. **Per-tenant rate limits and quotas** at APIM and at each service. AI cost caps are per-tenant.
9. **Per-tenant kill switch**: HALT flag in Risk FSM is tenant-scoped.
10. **Per-tenant secrets**: Alpaca tokens encrypted with tenant-specific Key Vault key.

## Pattern

```java
public interface TradeRepository {
    Optional<Trade> findById(UUID tradeId);   // implementation enforces WHERE tenant_id = TenantContext.current()
    List<Trade> findOpenForToday();           // same
}
```

```java
@Component
public class TenantContextFilter implements Filter {
    public void doFilter(Request req, Response res, FilterChain chain) {
        var tenantId = JwtClaims.from(req).require("tenant_id");
        TenantContext.set(tenantId);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
```

## Anti-patterns to flag

- `findAll()` without tenant scope
- Static singleton caches without tenant key
- Tools that take a free-form `tenantId` parameter
- Kafka topics with no partition key
- Tests that don't verify isolation
- Schema migrations that forget `tenant_id` on a new table
