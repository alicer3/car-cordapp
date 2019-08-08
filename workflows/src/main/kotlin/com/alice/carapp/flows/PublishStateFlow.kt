package com.alice.carapp.flows

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.PublishedStateContract
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException
import javax.jws.WebParam

@InitiatingFlow
class PublishStateFlow<T: ContractState>(val state: T, private val mode: ModeEnum = ModeEnum.NEWISSUE) : FlowLogic<StateAndRef<PublishedState<T>>>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): StateAndRef<PublishedState<T>> {
        return when(mode){
            ModeEnum.NEWISSUE -> issueNew()
            ModeEnum.REUSE -> reuse()
        }
    }

    @Suspendable
    private fun issueNew(): StateAndRef<PublishedState<T>> {
        val published = PublishedState(state, ourIdentity)
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if(!findState(this, state, state.javaClass)) throw IllegalArgumentException("There is no such state in vault.")

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
        return subFlow(FinalityFlow(stx, targetSession)).tx.outRef(0)
    }

    @Suspendable
    private fun reuse(): StateAndRef<PublishedState<T>> = found()?: issueNew()

    private fun found(): StateAndRef<PublishedState<T>>?{
        val results = serviceHub.vaultService.queryBy(PublishedState::class.java).states
        val filtered = results.filter {
            it.state.data.data == state && it.state.data.owner == ourIdentity
        }

        if(filtered.isNotEmpty())
            return filtered.first() as StateAndRef<PublishedState<T>>

        return null
    }

}

@CordaSerializable
enum class ModeEnum {
    NEWISSUE,
    REUSE
}

@InitiatedBy(PublishStateFlow::class)
class PublishStateFlowResponder<T: ContractState>(private val flowSession: FlowSession) : FlowLogic<Unit>() {
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

private fun <T: ContractState> findState(flow: FlowLogic<Any>, state: T, clazz: Class<T>): Boolean {
    val results = flow.serviceHub.vaultService.queryBy(clazz).states
    return results.any {
        it.state.data == state
    }

}



