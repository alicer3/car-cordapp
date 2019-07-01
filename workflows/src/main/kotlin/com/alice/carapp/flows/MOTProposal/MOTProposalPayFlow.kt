package com.alice.carapp.flows.MOTProposal

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import com.r3.corda.lib.tokens.workflows.flows.evolvable.UpdateEvolvableToken
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens

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
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException
import java.util.*
import javax.annotation.Signed

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
//        val cashBalance = serviceHub.getCashBalance(input.price.token)
//        if (cashBalance < input.price) {
//            throw IllegalArgumentException("Owner has only $cashBalance but needs ${input.price} to pay.")
//        }
//        val (_, cashKeys) = CashUtils.generateSpend(serviceHub, txBuilder, input.price, ourIdentityAndCert, input.tester)
//
//        // We create the transaction components.
//        val command = Command(MOTProposalContract.Commands.Pay(), input.participants.map { it.owningKey })
//
//        // We create a transaction builder and add the components.
//        txBuilder.addInputState(stateAndRef)
//                .addOutputState(input.copy(status = StatusEnum.PAID), MOTProposalContract.ID)
//                .addCommand(command)
//
//        // Verifying the transaction.
//        txBuilder.verify(serviceHub)
//
//        val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
        val cashBalance = serviceHub.vaultService.tokenBalance(input.price.token)
        if(cashBalance < input.price) throw IllegalArgumentException("Owner has only $cashBalance but needs ${input.price} to pay.")

        addMoveTokens(txBuilder, input.price, input.tester, ourIdentity)

        val command = Command(MOTProposalContract.Commands.Pay(), input.participants.map { it.owningKey })
        txBuilder.addInputState(stateAndRef)
                .addOutputState(input.copy(status = StatusEnum.PAID), MOTProposalContract.ID)
                .addCommand(command)
        val myKeysToSign = txBuilder.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)
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
class SelfIssueCashFlow(val amount: Amount<FiatCurrency>, val receiver: Party) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {

        val token = amount issuedBy ourIdentity heldBy receiver

        val flows = token.participants.map{ party -> initiateFlow(party as Party) }
        val flow = IssueTokensFlow(token, flows, emptyList())

        subFlow(flow)
    }

    @InitiatedBy(SelfIssueCashFlow::class)
    class SelfCashIssueFlowResponse (val flowSession: FlowSession): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(IssueTokensFlowHandler(flowSession))
        }
    }
}