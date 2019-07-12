package com.alice.carapp.states

import com.alice.carapp.contracts.MOTContract
import com.alice.carapp.helper.Vehicle
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.util.*

@BelongsToContract(MOTContract::class)
data class MOT(val testDate: Date,
          val expiryDate: Date,
          val locOfTest: String,
          val tester: Party,
          var vehicle: Vehicle,
          val owner: Party,
          val result: Boolean,
               override val linearId: UniqueIdentifier = UniqueIdentifier()): ContractState, LinearState{
    override val participants get() = listOf(tester, owner)
    constructor(proposal: MOTProposal, testDate: Date, expiryDate: Date, locOfTest: String, result: Boolean): this(testDate, expiryDate, locOfTest, proposal.tester, proposal.vehicle, proposal.owner, result)
}