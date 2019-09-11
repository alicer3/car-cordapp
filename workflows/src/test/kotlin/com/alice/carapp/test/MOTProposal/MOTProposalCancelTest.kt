package com.alice.carapp.test.motproposal

import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.flows.MOTProposal.MOTProposalAgreeFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalCancelFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.test.BaseTest
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MOTProposalCancelTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(MOTProposalIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    /*
    1. input status
    2. health check (I/O number & type, status)
     */

    /*
        the wrong input status
        a issue MOTProposal
        a distribute MOTProposal
     */
    @Test
    fun testInputStatus() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        assertFailsWith<TransactionVerificationException> { runFlow(MOTProposalCancelFlow(output.linearId), a) }
        runFlow(MOTProposalAgreeFlow(output.linearId), b)
        runFlow(MOTProposalCancelFlow(output.linearId), a)
    }

    // health check
    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val agreeTx = getAgreedProposal(a, b, a, b)
        val output = agreeTx.tx.outputs.single().data as MOTProposal
        val cancelTx = runFlow(MOTProposalCancelFlow(output.linearId), a)
        assert(cancelTx.tx.inputs.size == 1)
        assert(cancelTx.tx.outputs.isEmpty())
        assert(cancelTx.tx.inputs.single() == StateRef(agreeTx.id, 0))
        val command = cancelTx.tx.commands.single()
        assert(command.value is MOTProposalContract.Commands.Cancel)
        cancelTx.verifyRequiredSignatures()
        println("Signed transaction hash: ${cancelTx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(cancelTx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${cancelTx.id}")
            assertEquals(cancelTx.id, txHash)
        }
    }

}