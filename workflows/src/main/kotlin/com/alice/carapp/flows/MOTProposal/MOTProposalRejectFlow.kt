package com.alice.carapp.flows.MOTProposal

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException

@InitiatingFlow
@StartableByRPC
class MOTProposalRejectFlow(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val stateAndRef =  serviceHub.vaultService.queryBy<MOTProposal>(queryCriteria).states.single()
        val input = stateAndRef.state.data
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create the transaction components.
        val command = Command(MOTProposalContract.Commands.Reject(), listOf(ourIdentity.owningKey))

        if(input.actionParty != ourIdentity) throw IllegalArgumentException("Only the action party is authorized to initiate Reject Flow.")

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addInputState(stateAndRef)
                .addOutputState(input.copy(status = StatusEnum.REJECTED), MOTProposalContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (input.participants - ourIdentity).map { initiateFlow(it) }
        //val stx = subFlow(CollectSignaturesFlow(signedTx, targetSession))
        // Finalising the transaction.
        return subFlow(FinalityFlow(signedTx, targetSession))
    }
}

@InitiatedBy(MOTProposalRejectFlow::class)
class MOTProposalRejectFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ReceiveFinalityFlow(flowSession))
    }
}