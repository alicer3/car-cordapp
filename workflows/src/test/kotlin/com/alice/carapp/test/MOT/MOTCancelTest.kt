package com.alice.carapp.test.MOT

import com.alice.carapp.flows.MOT.MOTCancelFlow
import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOT.MOTIssueFlowResponder
import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.packageName
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue


class MOTCancelTest {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var vehicle: Vehicle
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


    fun getPaidProposal(): SignedTransaction {
        val proposal = MOTProposal(a.info.legalIdentities.first(), b.info.legalIdentities.first(), vehicle, 100.GBP, StatusEnum.DRAFT, a.info.legalIdentities.first())
        val issueFlow = MOTProposalIssueFlow(proposal)
        val distributeFlow = MOTProposalDistributeFlow(proposal.linearId, 100.GBP)
        val agreeFlow = MOTProposalAgreeFlow(proposal.linearId)
        runFlow(issueFlow, a)
        runFlow(distributeFlow, a)
        val tx = runFlow(agreeFlow, b)
        val payFlow = MOTProposalPayFlow(proposal.linearId)
        issueCash(159.GBP, b)
        return runFlow(payFlow, b)
    }

    fun getMOT(): SignedTransaction {
        val tx = getPaidProposal()
        val proposal = tx.tx.outputsOfType<MOTProposal>().single()

        calendar.add(Calendar.DATE, -1) // test date yesterday
        val testDate = calendar.time
        calendar.add(Calendar.YEAR, 1) // 1 year from now on
        val expiryDate = calendar.time

        val flow = MOTIssueFlow(proposal.linearId, testDate = testDate, expiryDate = expiryDate, loc = "loc", result = true)
        return  runFlow(flow, a)
    }


    fun runFlow(flow: FlowLogic<SignedTransaction>, ap: StartedMockNode): SignedTransaction {
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }


    private fun issueCash(amount: Amount<TokenType>, ap: StartedMockNode): Unit{
        val flow = SelfIssueCashFlow(amount, ap.info.legalIdentities.first())
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }



    /*
    input: mot
    output: none
    1. wrong issuing party
    2. health check
     */
    @Test
    fun testWrongParty() {
        val tx = getMOT()
        val mot = tx.tx.outputsOfType<MOT>().single()
        val cancelFlow = MOTCancelFlow(mot.linearId)
        assertFailsWith<IllegalArgumentException> { runFlow(cancelFlow, b) }
    }


    @Test
    fun healthCheck() {
        val tx = getMOT()
        val mot = tx.tx.outputsOfType<MOT>().single()
        val cancelFlow = MOTCancelFlow(mot.linearId)
        val ctx = runFlow(cancelFlow, a)
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