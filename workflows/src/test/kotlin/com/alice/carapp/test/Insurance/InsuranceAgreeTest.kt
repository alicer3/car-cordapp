package com.alice.carapp.test.insurance

import com.alice.carapp.flows.Insurance.InsuranceAgreeFlow
import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
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

class InsuranceAgreeTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var start: Date
    private lateinit var end: Date

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)
        val calendar = Calendar.getInstance()
        calendar.set(2019, 5, 1)
        start = calendar.time
        calendar.set(2020, 5, 31)
        end = calendar.time

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(InsuranceIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    /* the wrong party who started AgreeFlow
        a issue Insurance
        a distribute Insurance
        a agree Insurance
     */
    @Test
    fun testWrongActionParty() {
        val distributeTx = getDistributedInsurance(a, b, a, start, end, vehicle)
        val output = distributeTx.tx.outputs.single().data as Insurance
        val flow = InsuranceAgreeFlow(output.linearId)
        assertFailsWith<IllegalArgumentException> { runFlow(flow, a) }

    }

    /*
        the wrong input status
        a issue Insurance
        a agree Insurance
     */
    @Test
    fun testInputStatus() {
        val issueTx = getDraftInsurance(a, b, a, start, end, vehicle)
        val output = issueTx.tx.outputs.single().data as Insurance
        val flow = InsuranceAgreeFlow(output.linearId)
        assertFailsWith<TransactionVerificationException> { runFlow(flow, a) }
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val atx = getAgreedInsurance(a, b, a, b, start, end, vehicle)
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