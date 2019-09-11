package com.alice.carapp.flows

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.PublishedStateContract
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.Command
import net.corda.core.contracts.ContractState
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

@StartableByRPC
@InitiatingFlow
class RevokePublishedStateFlow(val state: ContractState) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {

        // Search for all published state for this given state
        val targetSession = (state.participants - ourIdentity).map { initiateFlow(it.toParty(serviceHub)) }
        targetSession.map {
            it.send(state)
        }

        subFlow(SelfRevokePublishedStateFlow(state))
    }

}

@InitiatedBy(RevokePublishedStateFlow::class)
class RevokePublishedStateFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val state = flowSession.receive<ContractState>().unwrap { it }
        subFlow(SelfRevokePublishedStateFlow(state))
    }
}

class SelfRevokePublishedStateFlow(val state: ContractState) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {

        val psOwnedByMe = serviceHub.vaultService.queryBy(PublishedState::class.java).states
                .filter {
                    it.state.data.data == state && it.state.data.owner == ourIdentity
                }

        if (psOwnedByMe.isEmpty()) return

        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val command = Command(PublishedStateContract.Commands.Revoke(), ourIdentity.owningKey)

        val txBuilder = TransactionBuilder(notary = notary)
                .addCommand(command)
        psOwnedByMe.map { txBuilder.addInputState(it) }

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val stx = serviceHub.signInitialTransaction(txBuilder)
        subFlow(FinalityFlow(stx, emptySet<FlowSession>()))

    }
}

