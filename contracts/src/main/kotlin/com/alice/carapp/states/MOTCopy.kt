package com.alice.carapp.states

import com.alice.carapp.contracts.MOTCopyContract
import net.corda.core.contracts.*
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria

@BelongsToContract(MOTCopyContract::class)
data class MOTCopy(val mot: MOT
//                   val owner: Party,
//                   val tester: Party,
               //override val linearId: UniqueIdentifier = UniqueIdentifier()
): ContractState {
    override val participants get() = listOf(mot.owner)
}