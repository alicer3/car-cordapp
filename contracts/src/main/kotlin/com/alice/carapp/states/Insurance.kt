package com.alice.carapp.states


import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.helper.Vehicle
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.contracts.*
import net.corda.core.identity.Party
import java.util.*

@BelongsToContract(InsuranceContract::class)
data class Insurance(val insurancer: Party,
                     val insured: Party,
                     val vehicle: Vehicle,
                     val price: Amount<TokenType>,
                     val coverage: String,
                     val effectiveDate: Date,
                     val expiryDate: Date,
                     val actionParty: Party,
                     val status: StatusEnum,
                     override val linearId: UniqueIdentifier = UniqueIdentifier()) : ContractState, LinearState {
    override val participants get() = listOf(insurancer, insured)
}