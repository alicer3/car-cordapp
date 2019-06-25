package com.alice.carapp.flows.MOT


import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTContract
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTProposal
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
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
@StartableByRPC
class MOTIssueFlow(val linearId: UniqueIdentifier, val testDate: Date, val expiryDate: Date, val loc: String, val result: Boolean) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val stateAndRef =  serviceHub.vaultService.queryBy<MOTProposal>(queryCriteria).states.single()
        val input = stateAndRef.state.data
        val mot = MOT(testDate = testDate, expiryDate = expiryDate, locOfTest = loc, proposal = input, result = result)
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if(ourIdentity != input.tester) throw IllegalArgumentException("Only tester could initiate MOT issue flow.")


        // We create the transaction components.
        val command = Command(MOTContract.Commands.Issue(), ourIdentity.owningKey)
        val command2 = Command(MOTProposalContract.Commands.Consume(), input.participants.map { it.owningKey })

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addInputState(stateAndRef)
                .addOutputState(mot, MOTContract.ID)
                .addCommand(command)
                .addCommand(command2)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (input.participants - ourIdentity).map { initiateFlow(it) }
        val stx = subFlow(CollectSignaturesFlow(signedTx, targetSession))
        // Finalising the transaction.
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(MOTIssueFlow::class)
class MOTIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {

            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}