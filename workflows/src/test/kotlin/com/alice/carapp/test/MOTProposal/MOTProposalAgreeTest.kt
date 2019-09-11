package com.alice.carapp.test.motproposal

import com.alice.carapp.flows.MOTProposal.MOTProposalAgreeFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
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

class MOTProposalAgreeTest : BaseTest() {
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

    /* the wrong party who started AgreeFlow
        a issue MOTProposal
        a distribute MOTProposal
        a agree MOTProposal
     */
    @Test
    fun testWrongActionParty() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalAgreeFlow(output.linearId)
        assertFailsWith<IllegalArgumentException> { runFlow(flow, a) }

    }

    /*
        the wrong input status
        a issue MOTProposal
        a agree MOTProposal
     */
    @Test
    fun testInputStatus() {
        val itx = getIssuedProposal(a, b, a)
        val output = itx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalAgreeFlow(output.linearId)
        assertFailsWith<TransactionVerificationException> { runFlow(flow, a) }
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val atx = getAgreedProposal(a, b, a, b)
        atx.verifyRequiredSignatures()
        println("Signed transaction hash: ${atx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(atx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${atx.id}")
            assertEquals(atx.id, txHash)
        }
    }
}