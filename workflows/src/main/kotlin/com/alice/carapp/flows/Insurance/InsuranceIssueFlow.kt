package com.alice.carapp.flows.Insurance

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.flows.PublishStateFlow
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.PublishedStateContract
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.MOT
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.money.GBP
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.ourSigningKeys
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.lang.IllegalArgumentException


@InitiatingFlow
@StartableByRPC
class InsuranceIssueFlow(val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val stateAndRef =  serviceHub.vaultService.queryBy<Insurance>(queryCriteria).states.single()
        val input = stateAndRef.state.data

        if(ourIdentity != input.insurancer)
            throw IllegalArgumentException("Only the insurancer could initiate the issue flow.")

        val targetSession = initiateFlow(input.insured)

        // send the state and ref of Agreed Insurance
        subFlow(SendStateAndRefFlow(targetSession, listOf(stateAndRef)))
        subFlow(IdentitySyncFlow.Receive(targetSession))

        //receive and check the signed transaction from insured
        val signedTransactionFlow = object : SignTransactionFlow(targetSession) {
            override fun checkTransaction(stx: SignedTransaction) {

            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(targetSession, txWeJustSignedId.id))
    }
}

@InitiatedBy(InsuranceIssueFlow::class)
class InsuranceIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()
    @Suspendable
    override fun call(): SignedTransaction {

        // receive state and ref of Insurance
        val receivedInsurance = subFlow(ReceiveStateAndRefFlow<Insurance>(flowSession)).single()

        val mot = findMOT(receivedInsurance.state.data.vehicle)
        if (mot == null) throw IllegalArgumentException("No MOT found for this vehicle.")

        val mcptx = subFlow(PublishStateFlow(mot.state.data))
        val motCopy = mcptx.tx.outRefsOfType<PublishedState<MOT>>().single()

        val cashBalance = serviceHub.vaultService.tokenBalance(GBP)
        if(cashBalance < receivedInsurance.state.data.price) throw IllegalArgumentException("Not enough cash to pay for insurance!")


        // build transaction and sign
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val tx = TransactionBuilder(notary)
        //val (tx, cashKeys) = CashUtils.generateSpend(serviceHub, ptx, receivedInsurance.state.data.price, ourIdentityAndCert, receivedInsurance.state.data.insurancer)
        val command = Command(InsuranceContract.Commands.Issue(), receivedInsurance.state.data.participants.map { it.owningKey })
        val command2 = Command(PublishedStateContract.Commands.Consume(), motCopy.state.data.data.owner.owningKey)
        tx.addInputState(receivedInsurance).addInputState(motCopy)//.addReferenceState(ReferencedStateAndRef(motCopy))
                .addOutputState(receivedInsurance.state.data.copy(status = StatusEnum.ISSUED), InsuranceContract.ID)
                .addCommand(command)
                .addCommand(command2)
        addMoveFungibleTokens(tx, serviceHub, listOf(PartyAndAmount(receivedInsurance.state.data.insurancer, receivedInsurance.state.data.price)), ourIdentity)
        //addMoveTokens(tx, receivedInsurance.state.data.price, receivedInsurance.state.data.insurancer, ourIdentity)
        tx.verify(serviceHub)

        val keys = tx.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)
        val partedSignedTx = serviceHub.signInitialTransaction(tx, keys)

        // send back partially signed transaction
        subFlow(IdentitySyncFlow.Send(flowSession, tx.toWireTransaction(serviceHub)))
        val insurancerSign = subFlow(CollectSignaturesFlow(partedSignedTx, listOf(flowSession).toSet(), keys))
        val twiceSigned = partedSignedTx.plus(insurancerSign.sigs)

        return subFlow(FinalityFlow(transaction = twiceSigned, sessions = setOf(flowSession)))
    }

    private fun findMOT(vehicle: Vehicle): StateAndRef<MOT>? {
        val results =  serviceHub.vaultService.queryBy<MOT>().states
        val filtered = results.filter {
            it.state.data.vehicle == vehicle && it.state.data.result && it.state.data.owner == ourIdentity
        }
        if (filtered.isNotEmpty()) {
            val sorted = filtered.sortedBy { it.state.data.expiryDate }
            return sorted.first()
        }else
            return null

    }

}