package com.alice.carapp.test.MOTProposal

import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import com.r3.corda.lib.tokens.money.USD
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.packageName
import net.corda.core.node.services.queryBy
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
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class MOTProposalPayTest {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var vehicle: Vehicle

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

    fun issueProposal(ap: StartedMockNode): SignedTransaction { // default a as tester and b as owner
        val proposal = MOTProposal(a.info.legalIdentities.first(), b.info.legalIdentities.first(), vehicle, 100.GBP, StatusEnum.DRAFT, ap.info.legalIdentities.first())
        val flow = MOTProposalIssueFlow(proposal)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun distributeMOTProposal(linearId: UniqueIdentifier, newPrice: Amount<FiatCurrency>, ap: StartedMockNode): SignedTransaction {
        val flow = MOTProposalDistributeFlow(linearId, newPrice)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun agreeMOTProposal(linearId: UniqueIdentifier, ap: StartedMockNode): SignedTransaction {
        val flow = MOTProposalAgreeFlow(linearId)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun rejectMOTProposal(linearId: UniqueIdentifier, ap: StartedMockNode): SignedTransaction {
        val flow = MOTProposalRejectFlow(linearId)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()

    }

    fun updateMOTProposal(linearId: UniqueIdentifier, newPrice: Amount<FiatCurrency>, ap: StartedMockNode): SignedTransaction {
        val flow = MOTProposalUpdateFlow(linearId, newPrice)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun payMOTProposal(linearId: UniqueIdentifier, ap: StartedMockNode): SignedTransaction {
        val flow = MOTProposalPayFlow(linearId)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    private fun issueCash(amount: Amount<FiatCurrency>, ap: StartedMockNode) {
        val flow = SelfIssueCashFlow(amount, ap.info.legalIdentities.first())
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }
    /*
    1. input status
    2. wrong initiate party
    3. not enough cash/wrong cash/no cash
    health check
     */

    @Test
    fun testWrongInputStatus(){
        val issueTx = issueProposal(a)
        println(issueTx.tx.outputs.single())
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        val dtx = distributeMOTProposal(linearId, 100.GBP, a)
        val tx = rejectMOTProposal(linearId, b)
        issueCash(100.GBP, b)
        assertFailsWith<TransactionVerificationException> { payMOTProposal(linearId, b) }
    }

    @Test
    fun testWrongParty(){
        val issueTx = issueProposal(a)
        println(issueTx.tx.outputs.single())
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        agreeMOTProposal(linearId, b)
        assertFailsWith<IllegalArgumentException> { payMOTProposal(linearId, a) }
    }

    @Test
    fun testNotEnoughCash() {
        val issueTx = issueProposal(a)
        println(issueTx.tx.outputs.single())
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        agreeMOTProposal(linearId, b)
        assertFailsWith<IllegalArgumentException> { payMOTProposal(linearId, b) }
        issueCash(30.GBP, b)
        assertFailsWith<IllegalArgumentException> { payMOTProposal(linearId, b) }
        issueCash(130.USD, b)
        assertFailsWith<IllegalArgumentException> { payMOTProposal(linearId, b) }
        issueCash(100.GBP, b)
        payMOTProposal(linearId, b)
    }

    @Test
    fun healthCheck() {
        val issueTx = issueProposal(a)
        println(issueTx.tx.outputs.single())
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        agreeMOTProposal(linearId, b)
        issueCash(130.GBP, b)
        val tx = payMOTProposal(linearId, b)
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