package com.alice.carapp.test.motproposal

import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.flows.MOTProposal.MOTProposalRejectFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.test.BaseTest
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

class MOTProposalRejectTest : BaseTest() {
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

    // Reject Flow test
    /*
    1. input status
    2. action party
    3. health check (I/O number & type, status)
     */

    /* the wrong party who started AgreeFlow
        a issue MOTProposal
        a distribute MOTProposal
        a reject MOTProposal
     */
    @Test
    fun testWrongActionParty() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        assertFailsWith<IllegalArgumentException> { runFlow(MOTProposalRejectFlow(output.linearId), a) }
    }

    /*
        the wrong input status
        a issue MOTProposal
        a distribute MOTProposal
        b agree MOTProposal
        a reject MOTProposal
     */
    @Test
    fun testInputStatus() {
        val atx = getAgreedProposal(a, b, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal
        assertFailsWith<TransactionVerificationException> { runFlow(MOTProposalRejectFlow(output.linearId), b) }
    }

    // health check
    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        val rejectTx = runFlow(MOTProposalRejectFlow(output.linearId), b)
        rejectTx.verifyRequiredSignatures()
        println("Signed transaction hash: ${rejectTx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(rejectTx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${rejectTx.id}")
            assertEquals(rejectTx.id, txHash)
        }
    }


}