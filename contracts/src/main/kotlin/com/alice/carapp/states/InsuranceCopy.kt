package com.alice.carapp.states

import com.alice.carapp.contracts.InsuranceCopyContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState

@BelongsToContract(InsuranceCopyContract::class)
data class InsuranceCopy(val insurance: Insurance
): ContractState {
    override val participants get() = listOf(insurance.insured)
}