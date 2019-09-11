package com.alice.carapp.test.mot

import com.alice.carapp.flows.MOT.MOTCancelFlow
import com.alice.carapp.flows.MOT.MOTIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOT
import com.alice.carapp.test.BaseTest
import net.corda.core.contracts.StateRef
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


class MOTCancelTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var testDate: Date
    private lateinit var expiryDate: Date

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)
        setDates()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(MOTIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    private fun setDates() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DATE, -1) // test date yesterday
        testDate = calendar.time
        calendar.add(Calendar.YEAR, 1) // 1 year from now on
        expiryDate = calendar.time
    }


    /*
    input: mot
    output: none
    1. wrong issuing party
    2. health check
     */
    @Test
    fun testWrongParty() {
        val tx = getIssuedMOT(a, b, testDate, expiryDate)
        val mot = tx.tx.outputsOfType<MOT>().single()
        val cancelFlow = MOTCancelFlow(mot.linearId)
        assertFailsWith<IllegalArgumentException> { runFlow(cancelFlow, a) }
    }


    @Test
    fun healthCheck() {
        val tx = getIssuedMOT(a, b, testDate, expiryDate)
        val mot = tx.tx.outputsOfType<MOT>().single()
        val cancelFlow = MOTCancelFlow(mot.linearId)
        val ctx = runFlow(cancelFlow, b)
        ctx.verifyRequiredSignatures()
        assertEquals(ctx.tx.inputs.size, 1)
        assertEquals(ctx.tx.inputs.single(), StateRef(tx.id, 0))
        assert(ctx.tx.outputs.isEmpty())
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(ctx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${ctx.id}")
            assertEquals(ctx.id, txHash)
        }
    }
}