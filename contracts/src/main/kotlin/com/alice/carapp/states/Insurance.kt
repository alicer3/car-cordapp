package com.alice.carapp.states

import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.helper.Vehicle
import net.corda.core.contracts.*
import net.corda.core.identity.Party
import java.util.*

@BelongsToContract(InsuranceContract::class)
data class Insurance(val insurancer: Party,
                     val insured: Party,
                     val vehicle: Vehicle,
                     val price: Amount<Currency>,
                     val coverage: String,
                     val effectiveDate: Date,
                     val expiryDate: Date,
                     val actionParty: Party,
                     val status: StatusEnum,
               override val linearId: UniqueIdentifier = UniqueIdentifier()): ContractState, LinearState {
    override val participants get() = listOf(insurancer, insured)
    //constructor(proposal: MOTProposal, testDate: Date, expiryDate: Date, locOfTest: String, result: Boolean): this(testDate, expiryDate, locOfTest, proposal.tester, proposal.vehicle, proposal.owner, result) {}
}