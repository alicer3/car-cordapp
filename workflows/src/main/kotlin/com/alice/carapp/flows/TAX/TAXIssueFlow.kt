package com.alice.carapp.flows.TAX

import co.paralleluniverse.fibers.Suspendable
import com.alice.carapp.contracts.TAXContract
import com.alice.carapp.flows.InsuranceCopy.InsuranceCopyIssueFlow
import com.alice.carapp.flows.MOTCopy.MOTCopyIssueFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.*
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
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
import java.lang.IllegalArgumentException

// owner
@InitiatingFlow
@StartableByRPC
class TAXIssueFlow(val tax: TAX) : FlowLogic<SignedTransaction>() {

    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()

    /** The flow logic is encapsulated within the call() method. */
    @Suspendable
    override fun call(): SignedTransaction {

        if(ourIdentity != tax.owner)
            throw IllegalArgumentException("Only owner could initiate the TAX issue flow.")
        if(!tax.LTA.name.organisation.equals("LTA"))
            throw IllegalArgumentException("The other party should be LTA.")

        val targetSession = initiateFlow(tax.LTA)

        val mot = findMOT(tax.vehicle)
        if (mot == null) throw IllegalArgumentException("No MOT found for this vehicle.")

        val mcptx = subFlow(MOTCopyIssueFlow(mot.state.data))
        val motCopy = mcptx.tx.outRefsOfType<MOTCopy>().single()

        // search insurance
        val insurance = findInsurance(tax.vehicle)
        if(insurance == null) throw IllegalArgumentException("No Insurance found for this vehicle.")
        val icptx = subFlow(InsuranceCopyIssueFlow(insurance.state.data))
        val insuranceCopy = icptx.tx.outRefsOfType<InsuranceCopy>().single()

        val cashBalance = serviceHub.vaultService.tokenBalance(TAX.price.token)
        if(cashBalance < TAX.price) throw IllegalArgumentException("Not enough cash to pay for Tax!")


        // build transaction and sign
        val notary = serviceHub.networkMapCache.notaryIdentities[0]
        val tx = TransactionBuilder(notary)


        addMoveTokens(tx, TAX.price, tax.LTA, ourIdentity)
        val cashKeys = tx.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)


        val command = Command(TAXContract.Commands.Issue(), tax.participants.map { it.owningKey })
        tx.addInputState(motCopy).addInputState(insuranceCopy)
                .addOutputState(tax, TAXContract.ID)
                .addCommand(command)
        tx.verify(serviceHub)
        val keys = (cashKeys.toSet() + ourIdentity.owningKey).toSet()
        val partedSignedTx = serviceHub.signInitialTransaction(tx, keys)
        subFlow(IdentitySyncFlow.Send(targetSession, tx.toWireTransaction(serviceHub)))

        // send back partially signed transaction
        val insurancerSign = subFlow(CollectSignaturesFlow(partedSignedTx, listOf(targetSession).toSet(), keys))

        //val twiceSigned = partedSignedTx.plus(insurancerSign.sigs)

        return subFlow(FinalityFlow(transaction = insurancerSign, sessions = setOf(targetSession)))
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

    private fun findInsurance(vehicle: Vehicle): StateAndRef<Insurance>? {
        val results =  serviceHub.vaultService.queryBy<Insurance>().states
        val filtered = results.filter {
            it.state.data.vehicle == vehicle && it.state.data.status == StatusEnum.ISSUED && it.state.data.insured == ourIdentity
        }
        if (filtered.isNotEmpty()) {
            val sorted = filtered.sortedBy { it.state.data.expiryDate }
            return sorted.first()
        }else
            return null
    }
}

//LTA
@InitiatedBy(TAXIssueFlow::class)
class TAXIssueFlowResponder(private val flowSession: FlowSession) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()
    @Suspendable
    override fun call(): SignedTransaction {

        subFlow(IdentitySyncFlow.Receive(flowSession))
        if(!ourIdentity.name.organisation.equals("LTA")) throw IllegalArgumentException("Only LTA could respond to TAX Issue flow.")

        //receive and check the signed transaction from insured
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
                requireThat {
                    stx.inputs.map {
                        val txHash = it.txhash
                        val tx = serviceHub.validatedTransactions.getTransaction(txHash)
                        if (tx != null) tx.verifyRequiredSignatures()
                    }

                }
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(flowSession, txWeJustSignedId.id))


    }

}