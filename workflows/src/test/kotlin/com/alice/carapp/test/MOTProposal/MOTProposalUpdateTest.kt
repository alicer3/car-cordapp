package com.alice.carapp.test.MOTProposal

import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow

import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class MOTProposalUpdateTest {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var vehicle: Vehicle

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary","London","GB"))))
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

    /*
    1. input status
    2. price (owner update with lower price)
    3. price (tester update with higher price)
    4. health check
     */

    // wrong input status
    @Test
    fun testInputStatus() {
        val issueTx = issueProposal(a)
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        distributeMOTProposal(linearId, 150.GBP, b)
        assertFailsWith<TransactionVerificationException> {updateMOTProposal(linearId, 200.GBP, a)}
    }

    /*
    a issue MOTProposal (a as tester, b as owner)
    a distribute MOTProposal
    b agree MOTProposal
    a update MOTProposal with higher price
     */
    @Test
    fun testHigherPriceWithOwner(){
        val issueTx = issueProposal(a)
        println(issueTx.tx.outputs.single())
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        agreeMOTProposal(linearId, b)
        assertFails { updateMOTProposal(linearId, 150.GBP, a) }
    }

    /*
    a issue MOTProposal (a as tester, b as owner)
    a distribute MOTProposal
    b agree MOTProposal
    b update MOTProposal with lower price
     */
    @Test
    fun testLowerPriceWithTester(){
        val issueTx = issueProposal(a)
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        agreeMOTProposal(linearId, b)
        assertFails{updateMOTProposal(linearId, 50.GBP, b)}
    }

    @Test
    fun flowReturnsCorrectlyFormedDuallySignedTransaction(){
        val issueTx = issueProposal(a)
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        distributeMOTProposal(linearId, 100.GBP, a)
        agreeMOTProposal(linearId, b)
        val updateTx = updateMOTProposal(linearId, 50.GBP, a)
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