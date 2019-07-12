package com.alice.carapp.states

import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.helper.Vehicle
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.util.*

@BelongsToContract(MOTProposalContract::class)
data class MOTProposal(val tester: Party,
                       val owner: Party,
                       var vehicle: Vehicle,
                       var price: Amount<TokenType>,
                       var status: StatusEnum,
                       var actionParty: Party,
                       override val linearId : UniqueIdentifier = UniqueIdentifier()): LinearState, ContractState {
    override val participants get() = listOf(tester, owner)
}

@CordaSerializable
enum class StatusEnum {
    DRAFT,
    PENDING,
    REJECTED,
    AGREED,
    PAID,
    ISSUED
}