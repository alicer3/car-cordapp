package com.alice.carapp.flows.MOTProposal

// Add these imports:
import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOTProposal
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException

// Replace Initiator's definition with:
@InitiatingFlow
@StartableByRPC
class MOTProposalIssueFlow(val proposal: MOTProposal) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call():SignedTransaction {
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create the transaction components.
        val command = Command(MOTProposalContract.Commands.Draft(), listOf(ourIdentity.owningKey))

        if(proposal.actionParty != ourIdentity) throw IllegalArgumentException("The action party should be issuer himself, so that he could distribute the proposal later.")

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(proposal, MOTProposalContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (proposal.participants - ourIdentity).map { initiateFlow(it) }
        // Finalising the transaction.
        return subFlow(FinalityFlow(signedTx, targetSession))
    }
}

@InitiatedBy(MOTProposalIssueFlow::class)
class MOTProposalIssueFlowResponder(private val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {

        subFlow(ReceiveFinalityFlow(otherPartySession))
    }
}