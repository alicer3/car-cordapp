package com.alice.carapp.flows.MOTProposal

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.workflows.asset.CashUtils
import net.corda.finance.workflows.getCashBalance
import java.lang.IllegalArgumentException
import java.util.*

@InitiatingFlow
@StartableByRPC
class MOTProposalPayFlow(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

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

        if(ourIdentity != input.owner) throw IllegalArgumentException("Only the owner could initiate the payment flow.")
        val txBuilder = TransactionBuilder(notary = notary)

        // deal with cash payment
        val cashBalance = serviceHub.getCashBalance(input.price.token)
        if (cashBalance < input.price) {
            throw IllegalArgumentException("Owner has only $cashBalance but needs ${input.price} to pay.")
        }
        val (_, cashKeys) = CashUtils.generateSpend(serviceHub, txBuilder, input.price, ourIdentityAndCert, input.tester)
        // We create the transaction components.
        val command = Command(MOTProposalContract.Commands.Pay(), input.participants.map { it.owningKey })

        // We create a transaction builder and add the components.
        txBuilder.addInputState(stateAndRef)
                .addOutputState(input.copy(status = StatusEnum.PAID), MOTProposalContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder, myKeysToSign)
        val targetSession = (input.participants - ourIdentity).map { initiateFlow(it) }
        subFlow(IdentitySyncFlow.Send(targetSession.toSet(), signedTx.tx))

        val stx = subFlow(CollectSignaturesFlow(signedTx, targetSession, myOptionalKeys = myKeysToSign))
        // Finalising the transaction.
        return subFlow(FinalityFlow(stx, targetSession))
    }
}

@InitiatedBy(MOTProposalPayFlow::class)
class MOTProposalPayFlowResponder(private val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        subFlow(IdentitySyncFlow.Receive(flowSession))
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}

/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
@InitiatingFlow
@StartableByRPC
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}