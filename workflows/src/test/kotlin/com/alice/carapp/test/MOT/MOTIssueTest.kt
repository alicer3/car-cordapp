package com.alice.carapp.test.mot

import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOT.MOTIssueFlowResponder
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
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith


class MOTIssueTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private val calendar = Calendar.getInstance()

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(MOTIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    /*
    input: paid proposal
    output: mot
    1. wrong proposal status
    2. wrong issuing party
    3. wrong mot details (dates, consistency with proposal)
    3. health check
     */

    @Test
    fun testWrongProposalStatus() {
        val tx = getAgreedProposal(a, b, a, b)
        val proposal = tx.tx.outputsOfType<MOTProposal>().single()

        calendar.add(Calendar.DATE, -1) // tested yesterday
        val testDate = calendar.time
        calendar.add(Calendar.YEAR, 1) // 1 year from now on
        val expiryDate = calendar.time

        val flow = MOTIssueFlow(proposal.linearId, testDate = testDate, expiryDate = expiryDate, loc = "loc", result = true)
        assertFailsWith<TransactionVerificationException> { runFlow(flow, b) }
    }

    @Test
    fun testWrongDates() {
        val tx = getPaidProposal(a, b)
        val proposal = tx.tx.outputsOfType<MOTProposal>().single()

        calendar.add(Calendar.DATE, 1) // tested tmr
        val wrongTestDate = calendar.time
        calendar.add(Calendar.DATE, -1) // tested yesterday
        val testDate = calendar.time
        calendar.add(Calendar.YEAR, 1) // 1 year from now on
        val expiryDate = calendar.time
        calendar.add(Calendar.YEAR, -1) // 1 year before
        val wrongExpiryDate = calendar.time


        val flow = MOTIssueFlow(proposal.linearId, testDate = wrongTestDate, expiryDate = expiryDate, loc = "loc", result = true)
        assertFailsWith<TransactionVerificationException> { runFlow(flow, b) }

        calendar.add(Calendar.DATE, -1)
        val flow2 = MOTIssueFlow(proposal.linearId, testDate = testDate, expiryDate = wrongExpiryDate, loc = "loc", result = true)
        assertFailsWith<TransactionVerificationException> { runFlow(flow2, b) }


        val flow3 = MOTIssueFlow(proposal.linearId, testDate = testDate, expiryDate = expiryDate, loc = "loc", result = true)
        runFlow(flow3, b)

    }

    @Test
    fun testWrongParty() {
        val tx = getPaidProposal(a, b)
        val proposal = tx.tx.outputsOfType<MOTProposal>().single()

        calendar.add(Calendar.DATE, -1) // test date yesterday
        val testDate = calendar.time
        calendar.add(Calendar.YEAR, 1) // 1 year from now on
        val expiryDate = calendar.time

        val flow = MOTIssueFlow(proposal.linearId, testDate = testDate, expiryDate = expiryDate, loc = "loc", result = true)
        assertFailsWith<IllegalArgumentException> { runFlow(flow, a) }

    }


    @Test
    fun healthCheck() {
        val ptx = getPaidProposal(a, b)
        ptx.verifyRequiredSignatures()
        val proposal = ptx.tx.outputsOfType<MOTProposal>().single()
        println(proposal)

        calendar.add(Calendar.DATE, -1) // test date yesterday
        val testDate = calendar.time
        calendar.add(Calendar.YEAR, 1) // 1 year from now on
        val expiryDate = calendar.time

        val flow = MOTIssueFlow(proposal.linearId, testDate = testDate, expiryDate = expiryDate, loc = "loc", result = true)
        val tx = runFlow(flow, b)
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