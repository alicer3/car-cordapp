package com.alice.carapp.test.motproposal


import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.flows.MOTProposal.MOTProposalPayFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalRejectFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.test.BaseTest
import com.r3.corda.lib.tokens.money.GBP
import com.r3.corda.lib.tokens.money.USD
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

class MOTProposalPayTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
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
    2. wrong initiate party
    3. not enough cash/wrong cash/no cash
    health check
     */

    @Test
    fun testWrongInputStatus() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        runFlow(MOTProposalRejectFlow(output.linearId), b)
        issueCash(output.price, a)
        assertFailsWith<TransactionVerificationException> { runFlow(MOTProposalPayFlow(output.linearId), a) }
    }

    @Test
    fun testWrongParty() {
        val atx = getAgreedProposal(a, b, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal
        issueCash(output.price, a)
        assertFailsWith<IllegalArgumentException> { runFlow(MOTProposalPayFlow(output.linearId), b) }
    }

    @Test
    fun testNotEnoughCash() {
        val atx = getAgreedProposal(a, b, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal

        assertFailsWith<IllegalArgumentException> { runFlow(MOTProposalPayFlow(output.linearId), a) }
        issueCash(output.price.minus(10.GBP), a)
        assertFailsWith<IllegalArgumentException> { runFlow(MOTProposalPayFlow(output.linearId), a) }
        issueCash(130.USD, a)
        assertFailsWith<IllegalArgumentException> { runFlow(MOTProposalPayFlow(output.linearId), a) }
        issueCash(10.GBP, a)
        runFlow(MOTProposalPayFlow(output.linearId), a)
    }

    @Test
    fun healthCheck() {
        val tx = getPaidProposal(a, b)
        tx.verifyRequiredSignatures()

        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(tx.id)

        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${tx.id}")
            assertEquals(tx.id, txHash)
        }
    }

}