package com.alice.carapp.flows.MOT

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTContract
import com.alice.carapp.states.MOT
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException
import java.util.*

@InitiatingFlow
@StartableByRPC
class MOTUpdateFlow(val linearId: UniqueIdentifier, val newTestDate: Date?, val newExpiryDate: Date?, val newResult: Boolean?) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val stateAndRef =  serviceHub.vaultService.queryBy<MOT>(queryCriteria).states.single()
        val input = stateAndRef.state.data
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if(ourIdentity != input.tester) throw IllegalArgumentException("Only tester could initiate MOT update flow.")

        // We create the transaction components.
        val command = Command(MOTContract.Commands.Update(), ourIdentity.owningKey)

        var output = input.copy()
        if (newTestDate != null) output = output.copy(testDate = newTestDate)
        if (newExpiryDate != null) output = output.copy(expiryDate = newExpiryDate)
        if (newResult != null) output = output.copy(result = newResult)

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addInputState(stateAndRef)
                .addOutputState(output)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (input.participants - ourIdentity).map { initiateFlow(it) }
        // Finalising the transaction.
        return subFlow(FinalityFlow(signedTx, targetSession))
    }
}

@InitiatedBy(MOTUpdateFlow::class)
class MOTUpdateFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ReceiveFinalityFlow(flowSession))
    }
}