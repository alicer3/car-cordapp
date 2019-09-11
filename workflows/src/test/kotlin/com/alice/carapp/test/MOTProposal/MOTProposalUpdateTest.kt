package com.alice.carapp.test.motproposal

import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.flows.MOTProposal.MOTProposalUpdateFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.test.BaseTest
import com.r3.corda.lib.tokens.money.GBP
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class MOTProposalUpdateTest : BaseTest() {
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
    2. price (owner update with lower price)
    3. price (tester update with higher price)
    4. health check
     */

    // wrong input status
    @Test
    fun testInputStatus() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        assertFailsWith<TransactionVerificationException> { runFlow(MOTProposalUpdateFlow(output.linearId, output.price.minus(10.GBP)), a) }
    }

    /*
    a issue MOTProposal (a as tester, b as owner)
    a distribute MOTProposal
    b agree MOTProposal
    a update MOTProposal with higher price
     */
    @Test
    fun testHigherPriceWithOwner() {
        val atx = getAgreedProposal(b, a, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal
        assertFails { runFlow(MOTProposalUpdateFlow(output.linearId, output.price.plus(10.GBP)), a) }
    }

    /*
    a issue MOTProposal (a as tester, b as owner)
    a distribute MOTProposal
    b agree MOTProposal
    b update MOTProposal with lower price
     */
    @Test
    fun testLowerPriceWithTester() {
        val atx = getAgreedProposal(b, a, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal
        assertFails { runFlow(MOTProposalUpdateFlow(output.linearId, output.price.minus(10.GBP)), b) }
    }

    @Test
    fun flowReturnsCorrectlyFormedDuallySignedTransaction() {
        val atx = getAgreedProposal(b, a, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal
        val updateTx = runFlow(MOTProposalUpdateFlow(output.linearId, output.price.minus(10.GBP)), a)
        updateTx.verifyRequiredSignatures()
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(updateTx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${updateTx.id}")
            assertEquals(updateTx.id, txHash)
        }
    }
}