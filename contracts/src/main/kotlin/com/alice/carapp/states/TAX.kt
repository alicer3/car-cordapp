package com.alice.carapp.states

import com.alice.carapp.contracts.TAXContract
import com.alice.carapp.helper.Vehicle
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.*
import net.corda.core.identity.Party
import java.util.*

@BelongsToContract(TAXContract::class)
data class TAX(
               val LTA: Party,
               val vehicle: Vehicle,
               val owner: Party,
               val effectiveDate: Date,
               val expiryDate: Date,
               override val linearId: UniqueIdentifier = UniqueIdentifier()): ContractState, LinearState {
    override val participants get() = listOf(LTA, owner)
    companion object {
        val price: Amount<FiatCurrency> = 1000.GBP
    }
    //constructor(proposal: MOTProposal, testDate: Date, expiryDate: Date, locOfTest: String, result: Boolean): this(testDate, expiryDate, locOfTest, proposal.tester, proposal.vehicle, proposal.owner, result) {}
}