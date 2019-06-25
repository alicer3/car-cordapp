package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.states.IOUState
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction

// Replace Responder's definition with:
@InitiatedBy(IOUFlow::class)
class IOUFlowResponder(private val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is IOUState)
                val iou = output as IOUState
                "The IOU's value can't be too high." using (iou.value < 100)
            }
        }

        val expectedTxId = subFlow(signTransactionFlow).id

        subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId))
    }
}

@InitiatedBy(Initiator::class)
class Responder(private val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ReceiveFinalityFlow(otherPartySession))
    }
}