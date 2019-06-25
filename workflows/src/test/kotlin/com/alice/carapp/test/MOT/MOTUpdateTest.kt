package com.alice.carapp.test.MOT

import com.alice.carapp.flows.MOT.MOTUpdateFlow
import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOT.MOTIssueFlowResponder
import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.packageName
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.schemas.CashSchemaV1
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue


class MOTUpdateTest {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var vehicle: Vehicle
    private val calendar = Calendar.getInstance()

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "net.corda.finance.contracts.asset", CashSchemaV1::class.packageName),
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
        val proposal = MOTProposal(a.info.legalIdentities.first(), b.info.legalIdentities.first(), vehicle, 100.POUNDS, StatusEnum.DRAFT, a.info.legalIdentities.first())
        val issueFlow = MOTProposalIssueFlow(proposal)
        val distributeFlow = MOTProposalDistributeFlow(proposal.linearId, 100.POUNDS)
        val agreeFlow = MOTProposalAgreeFlow(proposal.linearId)
        runFlow(issueFlow, a)
        runFlow(distributeFlow, a)
        val tx = runFlow(agreeFlow, b)
        val payFlow = MOTProposalPayFlow(proposal.linearId)
        issueCash(159.POUNDS, b)
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


    private fun issueCash(amount: Amount<Currency>, ap: StartedMockNode): Cash.State {
        val flow = SelfIssueCashFlow(amount)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }



    /*
    input: mot
    output: mot
    1. wrong issuing party
    2. wrong dates
    3. health check
     */
    @Test
    fun testWrongParty() {
        val tx = getMOT()
        val mot = tx.tx.outputsOfType<MOT>().single()
        val flow = MOTUpdateFlow(mot.linearId, mot.testDate, mot.expiryDate, newResult = true)
        assertFailsWith<IllegalArgumentException> { runFlow(flow, b) }
    }


    @Test
    fun healthCheck() {
        val tx = getMOT()
        val mot = tx.tx.outputsOfType<MOT>().single()
        val flow = MOTUpdateFlow(mot.linearId, mot.testDate, mot.expiryDate, newResult = true)
        val ctx = runFlow(flow, a)
        ctx.verifyRequiredSignatures()
        assertEquals(ctx.tx.inputs.size, 1)
        assertEquals(ctx.tx.inputs.single(), StateRef(tx.id, 0))
        assert(ctx.tx.outputs.size == 1)
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(ctx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${ctx.id}")
            assertEquals(ctx.id, txHash)
        }
    }
}