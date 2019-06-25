package com.template.flows

// Add these imports:
import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.IOUContract
import com.template.contracts.TemplateContract
import com.template.states.IOUState
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

// Replace Initiator's definition with:
@InitiatingFlow
@StartableByRPC
class IOUFlow(val iouValue: Int,
              val otherParty: Party) : FlowLogic<Unit>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call() {
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create the transaction components.
        val outputState = IOUState(iouValue, ourIdentity, otherParty)
        val command = Command(IOUContract.Create(), listOf(ourIdentity.owningKey, otherParty.owningKey))

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(outputState, IOUContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Creating a session with the other party.
        val otherPartySession = initiateFlow(otherParty)

        // Obtaining the counterparty's signature.
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherPartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        subFlow(FinalityFlow(fullySignedTx, otherPartySession))
    }
}

@InitiatingFlow
@StartableByRPC
class Initiator(val otherParty: Party) : FlowLogic<Unit>() {
    override val progressTracker: ProgressTracker = ProgressTracker()

    @Suspendable
    override fun call() {
        // Initiator flow logic goes here.
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create the transaction components.
        val outputState = IOUState(12, ourIdentity, otherParty)
        val command = Command(TemplateContract.Commands.Action(), ourIdentity.owningKey)

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(outputState, TemplateContract.ID)
                .addCommand(command)

        // We sign the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Creating a session with the other party.
        val otherPartySession = initiateFlow(otherParty)

        // We finalise the transaction and then send it to the counterparty.
        subFlow(FinalityFlow(signedTx, otherPartySession))
    }
}