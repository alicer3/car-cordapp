package com.alice.carapp.flows

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.PublishedStateContract
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.Command
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

// There is an issue for RPC client to call PublishStateFlow directly, thus creating a wrapper flow for RPC client to call
@StartableByRPC
class PublishStateWrapperFlow(val state: ContractState) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(PublishStateFlow(state))
    }
}

@InitiatingFlow
class PublishStateFlow<T : ContractState>(val state: T, private val mode: ModeEnum = ModeEnum.NEWISSUE) : FlowLogic<StateAndRef<PublishedState<T>>>() {

    @Suspendable
    override fun call(): StateAndRef<PublishedState<T>> {
        return when (mode) {
            ModeEnum.NEWISSUE -> issueNew()
            ModeEnum.REUSE -> reuse()
        }
    }

    // issue new PublishedState and return
    @Suspendable
    private fun issueNew(): StateAndRef<PublishedState<T>> {
        val published = PublishedState(state, ourIdentity)
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if (!findState(this, state, state.javaClass)) throw IllegalArgumentException("There is no such state in vault.")

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

    // search for and use existing PublishedState, otherwise issue new PublishedState
    @Suspendable
    private fun reuse(): StateAndRef<PublishedState<T>> = findPublished() ?: issueNew()

    private fun findPublished(): StateAndRef<PublishedState<T>>? {
        val results = serviceHub.vaultService.queryBy(PublishedState::class.java).states
        val filtered = results.filter {
            it.state.data.data == state && it.state.data.owner == ourIdentity
        }

        if (filtered.isNotEmpty())
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
class PublishStateFlowResponder<T : ContractState>(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                // retrieve the PublishedState to be signed
                val copy = stx.tx.outputsOfType<PublishedState<T>>().single()

                // retrieve the original state to be published
                val state = copy.data

                // search such original state in my vault
                if (!findState(this, state, state.javaClass)) throw IllegalArgumentException("No such state found in my vault!")

                // whether I am eligible to sign this Publish transaction
                if (ourIdentity !in state.participants) throw IllegalArgumentException("The responder should be state participants.")
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}

private fun <T : ContractState> findState(flow: FlowLogic<Any>, state: T, clazz: Class<T>): Boolean {
    val results = flow.serviceHub.vaultService.queryBy(clazz).states
    return results.any {
        it.state.data == state
    }

}



