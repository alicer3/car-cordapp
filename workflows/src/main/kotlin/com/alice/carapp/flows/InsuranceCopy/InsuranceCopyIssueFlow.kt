package com.alice.carapp.flows.InsuranceCopy

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.InsuranceCopyContract
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.InsuranceCopy
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
//@StartableByRPC
class InsuranceCopyIssueFlow(val insurance: Insurance) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val insurancecopy = InsuranceCopy(insurance)
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        if(!findInsurance(this, insurance.linearId)) throw IllegalArgumentException("There is no Insurance with this LinearId.")
        if(ourIdentity != insurance.insured)  throw IllegalArgumentException("Only the owner of the Insurance could issue InsuranceCopy.")
        // We create the transaction components.
        val command = Command(InsuranceCopyContract.Commands.Issue(), insurance.participants.map { it.owningKey })

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(insurancecopy, InsuranceCopyContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)
        val targetSession = (insurance.participants - ourIdentity).map { initiateFlow(it) }
        val stx = subFlow(CollectSignaturesFlow(signedTx, targetSession))
        // Finalising the transaction.
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(InsuranceCopyIssueFlow::class)
class InsuranceCopyIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val copy = stx.tx.outputsOfType<InsuranceCopy>().single()
                val insurance = copy.insurance
                if(!findInsurance(this, insurance.linearId)) throw IllegalArgumentException("No such Insurance found in my vault!")
                if(ourIdentity != insurance.insurancer) throw IllegalArgumentException("The responder should be Insurance tester.")
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}

private fun findInsurance(flow: FlowLogic<Any>, linearId: UniqueIdentifier): Boolean {
    val results = flow.serviceHub.vaultService.queryBy<Insurance>(QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))).states
    return (results.size == 1)

}