package com.alice.carapp.flows.MOTCopy

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTContract
import com.alice.carapp.contracts.MOTCopyContract
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTCopy
import com.alice.carapp.states.MOTProposal
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException
import java.util.Date

@InitiatingFlow
//@StartableByRPC
class MOTCopyIssueFlow(val mot: MOT) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val motcopy = MOTCopy(mot)
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if(!findMOT(this, mot.linearId)) throw IllegalArgumentException("There is no MOT with this LinearId.")
        if(ourIdentity != mot.owner)  throw IllegalArgumentException("Only the owner of the MOT could issue MOTCopy.")
        // We create the transaction components.
        val command = Command(MOTCopyContract.Commands.Issue(), mot.participants.map { it.owningKey })

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(motcopy, MOTCopyContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (mot.participants - ourIdentity).map { initiateFlow(it) }
        val stx = subFlow(CollectSignaturesFlow(signedTx, targetSession))
        // Finalising the transaction.
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(MOTCopyIssueFlow::class)
class MOTCopyIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val copy = stx.tx.outputsOfType<MOTCopy>().single()
                val mot = copy.mot
                if(!findMOT(this, mot.linearId)) throw IllegalArgumentException("No such MOT found in my vault!")
                if(ourIdentity != mot.tester) throw IllegalArgumentException("The responder should be MOT tester.")
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}

private fun findMOT(flow: FlowLogic<Any>, linearId: UniqueIdentifier): Boolean {
    val results = flow.serviceHub.vaultService.queryBy<MOT>(QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))).states
    return (results.size == 1)

}