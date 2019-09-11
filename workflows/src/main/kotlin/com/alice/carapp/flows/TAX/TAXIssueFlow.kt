package com.alice.carapp.flows.TAX

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.TAXContract
import com.alice.carapp.flows.PublishStateFlow
import com.alice.carapp.helper.PublishedStateContract
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.MOT
import com.alice.carapp.states.StatusEnum
import com.alice.carapp.states.TAX
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.ourSigningKeys
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

@InitiatingFlow
@StartableByRPC
class TAXIssueFlow(private val tax: TAX) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // check the parties on both sides
        if (ourIdentity != tax.owner)
            throw IllegalArgumentException("Only owner could initiate the TAX issue flow.")
        if (tax.LTA.name.organisation != "LTA")
            throw IllegalArgumentException("The other party should be LTA.")

        val targetSession = initiateFlow(tax.LTA)

        // search for MOT
        val mot = findMOT(tax.vehicle) ?: throw IllegalArgumentException("No MOT found for this vehicle.")

        // get Published MOT
        val motCopy = subFlow(PublishStateFlow(mot.state.data))

        // search insurance
        val insurance = findInsurance(tax.vehicle)
                ?: throw IllegalArgumentException("No Insurance found for this vehicle.")

        // get Published insurance
        val insuranceCopy = subFlow(PublishStateFlow(insurance.state.data))

        // check whether enough cash is present for payment
        val cashBalance = serviceHub.vaultService.tokenBalance(TAX.price.token)
        if (cashBalance < TAX.price) throw IllegalArgumentException("Not enough cash to pay for Tax!")

        // build transaction and sign
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val tx = TransactionBuilder(notary)

        addMoveFungibleTokens(tx, serviceHub, listOf(PartyAndAmount(tax.LTA, TAX.price)), ourIdentity)

        val cashKeys = tx.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)

        val command = Command(TAXContract.Commands.Issue(), tax.participants.map { it.owningKey })
        val command2 = Command(PublishedStateContract.Commands.Consume(), listOf(motCopy.state.data.owner.owningKey, insuranceCopy.state.data.owner.owningKey))

        tx.addInputState(motCopy).addInputState(insuranceCopy)
                .addOutputState(tax, TAXContract.ID)
                .addCommand(command).addCommand(command2)
        tx.verify(serviceHub)

        val keys = (cashKeys.toSet() + ourIdentity.owningKey).toSet()
        val partedSignedTx = serviceHub.signInitialTransaction(tx, keys)
        subFlow(IdentitySyncFlow.Send(targetSession, tx.toWireTransaction(serviceHub)))

        // send back partially signed transaction
        val insurancerSign = subFlow(CollectSignaturesFlow(partedSignedTx, listOf(targetSession).toSet(), keys))

        return subFlow(FinalityFlow(transaction = insurancerSign, sessions = setOf(targetSession)))
    }

    // search MOT for given vehicle
    private fun findMOT(vehicle: Vehicle): StateAndRef<MOT>? {
        val results = serviceHub.vaultService.queryBy<MOT>().states
        val filtered = results.filter {
            it.state.data.vehicle == vehicle && it.state.data.result && it.state.data.owner == ourIdentity
        }
        return if (filtered.isNotEmpty())
            filtered.maxBy { it.state.data.expiryDate }!!
        else
            null

    }

    // search insurance for given vehicle
    private fun findInsurance(vehicle: Vehicle): StateAndRef<Insurance>? {
        val results = serviceHub.vaultService.queryBy<Insurance>().states
        val filtered = results.filter {
            it.state.data.vehicle == vehicle && it.state.data.status == StatusEnum.ISSUED && it.state.data.insured == ourIdentity
        }
        return if (filtered.isNotEmpty())
            filtered.maxBy { it.state.data.expiryDate }!!
        else
            null
    }
}

@InitiatedBy(TAXIssueFlow::class)
class TAXIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()
    @Suspendable
    override fun call(): SignedTransaction {

        subFlow(IdentitySyncFlow.Receive(flowSession))

        if (ourIdentity.name.organisation != "LTA") throw IllegalArgumentException("Only LTA could respond to TAX Issue flow.")

        //receive and check the signed transaction from insured
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                requireThat {
                    stx.inputs.map {
                        val txHash = it.txhash
                        serviceHub.validatedTransactions.getTransaction(txHash)?.verifyRequiredSignatures()
                    }

                }
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(flowSession, txWeJustSignedId.id))


    }

}