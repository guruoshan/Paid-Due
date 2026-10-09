package com.dailymoneyregister.core.cloud.sync

import com.dailymoneyregister.core.database.DmrDatabase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

interface InboundEnvelopeApplier {
    suspend fun apply(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        sessionBusinessId: String,
        sessionBranchId: String?
    ): InboundProcessResult
}

class MaterializingInboundEnvelopeApplier @Inject constructor() : InboundEnvelopeApplier {
    override suspend fun apply(
        database: DmrDatabase,
        envelope: RemoteEnvelopeV2,
        sessionBusinessId: String,
        sessionBranchId: String?
    ): InboundProcessResult =
        RemoteEnvelopeMaterializer.materializeInboundEnvelope(
            database = database,
            envelope = envelope,
            sessionBusinessId = sessionBusinessId,
            sessionBranchId = sessionBranchId
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InboundEnvelopeApplierModule {
    @Binds
    abstract fun bindInboundEnvelopeApplier(
        implementation: MaterializingInboundEnvelopeApplier
    ): InboundEnvelopeApplier
}
