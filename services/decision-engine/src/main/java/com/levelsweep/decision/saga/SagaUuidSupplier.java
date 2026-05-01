package com.levelsweep.decision.saga;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * CDI producer that supplies the UUID factory used by {@link TradeSaga} to mint
 * its {@code correlationId} per saga run. Wrapping the raw
 * {@link Supplier Supplier&lt;UUID&gt;} in a dedicated producer keeps Quarkus's
 * type-safe injection from getting confused by ambient {@code Supplier<?>}
 * beans contributed by other libraries; the producer-method's return type
 * pins the parameterized form to the {@code @Inject Supplier<UUID>} site in
 * the saga.
 *
 * <p>Production maps to {@link UUID#randomUUID()}. Tests construct
 * {@code TradeSaga} directly via its public constructor with a deterministic
 * stub supplier so the replay harness in Phase 2 Step 7 can produce
 * bit-identical {@code correlationId}s across runs of the same input bar.
 */
@ApplicationScoped
public class SagaUuidSupplier {

    /**
     * Default {@link Supplier Supplier&lt;UUID&gt;} bean. {@code @ApplicationScoped}
     * so the same instance threads through every {@code TradeSaga} call —
     * harmless because {@link UUID#randomUUID()} is itself thread-safe.
     */
    @Produces
    @ApplicationScoped
    public Supplier<UUID> uuidSupplier() {
        return UUID::randomUUID;
    }
}
