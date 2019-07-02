package com.alice.carapp.flows

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.PublishedStateContract
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException

@InitiatingFlow
//@StartableByRPC
class PublishStateFlow<T: OwnableState>(val state: T) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val published = PublishedState(state)
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if(!findState(this, state, state.javaClass)) throw IllegalArgumentException("There is no such state in vault.")
        if(ourIdentity != state.owner)  throw IllegalArgumentException("Only the owner of the MOT could issue MOTCopy.")
        // We create the transaction components.
        val command = Command(PublishedStateContract.Commands.Issue(), state.participants.map { it.owningKey })

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(published, PublishedStateContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (state.participants - ourIdentity).map { initiateFlow(it.toParty(serviceHub)) }
        val stx = subFlow(CollectSignaturesFlow(signedTx, targetSession))
        // Finalising the transaction.
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(PublishStateFlow::class)
class PublishStateFlowResponder<T: OwnableState>(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val copy = stx.tx.outputsOfType<PublishedState<T>>().single()
                val state = copy.data
                if(!findState(this, state, state.javaClass)) throw IllegalArgumentException("No such state found in my vault!")
                if(ourIdentity !in state.participants) throw IllegalArgumentException("The responder should be state participants.")
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}

private fun <T: OwnableState> findState(flow: FlowLogic<Any>, state: T, clazz: Class<T>): Boolean {
    val results = flow.serviceHub.vaultService.queryBy(clazz).states
    return results.any {
        it.state.data == state
    }

}