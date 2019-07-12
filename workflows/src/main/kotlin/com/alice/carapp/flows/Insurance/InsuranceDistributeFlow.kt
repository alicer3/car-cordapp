package com.alice.carapp.flows.Insurance

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.states.Insurance

import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import net.corda.core.contracts.Amount
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
import java.util.*

@InitiatingFlow
@StartableByRPC
class InsuranceDistributeFlow(val linearId: UniqueIdentifier, val price: Amount<TokenType>, val cover: String, val effDate: Date, val expiryDate: Date) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val stateAndRef =  serviceHub.vaultService.queryBy<Insurance>(queryCriteria).states.single()
        val input = stateAndRef.state.data

        if (input.actionParty != ourIdentity) throw IllegalArgumentException("Only the action party could initiate distribute flow now.")
        val output = input.copy(price = price, coverage = cover,
                effectiveDate = effDate, expiryDate = expiryDate, actionParty = (input.participants - ourIdentity).first(), status = StatusEnum.PENDING)
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create the transaction components.
        val command = Command(InsuranceContract.Commands.Distribute(), input.participants.map { it.owningKey })

        //val distributed = draft.copy(status = StatusEnum.PENDING)
        // We create a transaction builder and add the components.
        val txBuilder = TransactionBuilder(notary = notary)
                .addInputState(stateAndRef)
                .addOutputState(output, InsuranceContract.ID)
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

@InitiatedBy(InsuranceDistributeFlow::class)
class InsuranceDistributeFlowResponder(private val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat{
                val output = stx.tx.outputs.single().data
                "Output should be Insurance." using (output is Insurance)
                val proposal = output as Insurance
                "The action party should be receiver oneself." using (proposal.actionParty == ourIdentity)
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}