package com.alice.carapp.flows.MOTProposal

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException

@InitiatingFlow
@StartableByRPC
class MOTProposalAgreeFlow(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

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
        val command = Command(MOTProposalContract.Commands.Agree(), input.participants.map { it.owningKey })

        if(input.actionParty != ourIdentity) throw IllegalArgumentException("Only the action party is authorized to initiate Agree Flow.")

        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addInputState(stateAndRef)
                .addOutputState(input.copy(status = StatusEnum.AGREED), MOTProposalContract.ID)
                .addCommand(command)

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

@InitiatedBy(MOTProposalAgreeFlow::class)
class MOTProposalAgreeFlowResponder(private val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val output = stx.tx.outputs.first().data as MOTProposal
                val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(output.linearId))
                val stateAndRef =  serviceHub.vaultService.queryBy<MOTProposal>(queryCriteria).states.single()
                val proposalInVault = stateAndRef.state.data
                requireThat {
                    "The received proposal is the same as latest in Vault." using (output == proposalInVault.copy(status = output.status))
                    "The receiver should be different from Action Party." using (output.actionParty != ourIdentity)
                }
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}