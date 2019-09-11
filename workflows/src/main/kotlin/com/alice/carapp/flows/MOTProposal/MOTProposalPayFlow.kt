package com.alice.carapp.flows.MOTProposal

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.utilities.ourSigningKeys
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class MOTProposalPayFlow(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // Retrieve MOTProposal by linearId
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val stateAndRef = serviceHub.vaultService.queryBy<MOTProposal>(queryCriteria).states.single()
        val input = stateAndRef.state.data

        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // Check the flow initiator is the owner
        if (ourIdentity != input.owner) throw IllegalArgumentException("Only the owner could initiate the payment flow.")

        // Build the transaction
        val txBuilder = TransactionBuilder(notary = notary)

        // check whether enough cash is available for payment
        val cashBalance = serviceHub.vaultService.tokenBalance(input.price.token)
        if (cashBalance < input.price) throw IllegalArgumentException("Owner has only $cashBalance but needs ${input.price} to pay.")

        // add cash transaction
        addMoveFungibleTokens(txBuilder, serviceHub, input.price, input.tester, ourIdentity)

        // add MOTProposal transaction
        val command = Command(MOTProposalContract.Commands.Pay(), input.participants.map { it.owningKey })
        txBuilder.addInputState(stateAndRef)
                .addOutputState(input.copy(status = StatusEnum.PAID), MOTProposalContract.ID)
                .addCommand(command)

        val myKeysToSign = txBuilder.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)
        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder, myKeysToSign)

        // Send to other participants for signature
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
class SelfIssueCashFlow(val amount: Amount<TokenType>, val receiver: Party) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {

        val token = amount issuedBy ourIdentity heldBy receiver

        val session = initiateFlow(receiver)
        val flow = IssueTokensFlow(token, listOf(session), listOf())

        subFlow(flow)
    }

    @InitiatedBy(SelfIssueCashFlow::class)
    class SelfCashIssueFlowResponse(val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(IssueTokensFlowHandler(flowSession))
        }
    }
}