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

import net.corda.testing.node.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MOTProposalRejectTest {
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

    fun issueProposal(ap: StartedMockNode): SignedTransaction {
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

    // Reject Flow test
    /*
    1. input status
    2. action party
    3. health check (I/O number & type, status)
     */

    /* the wrong party who started AgreeFlow
        a issue MOTProposal
        a distribute MOTProposal
        a reject MOTProposal
     */
    @Test
    fun testWrongActionParty() {
        val issueTx = issueProposal(a)
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        val distributeTx = distributeMOTProposal(linearId, 100.GBP, a)
        assertFailsWith<IllegalArgumentException> {rejectMOTProposal(linearId, a)}

    }

    /*
        the wrong input status
        a issue MOTProposal
        a distribute MOTProposal
     */
    @Test
    fun testInputStatus() {
        val issueTx = issueProposal(a)
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        val distributeTx = distributeMOTProposal(linearId, 100.GBP, a)
        distributeMOTProposal(linearId, 150.GBP, b)
        agreeMOTProposal(linearId, a)
        assertFailsWith<TransactionVerificationException> {rejectMOTProposal(linearId, a)}
    }

    // health check
    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val issueTx = issueProposal(a)
        val linearId = (issueTx.tx.outputs.single().data as MOTProposal).linearId
        val distributeTx = distributeMOTProposal(linearId, 100.GBP, a)
        val distributeTx2 = distributeMOTProposal(linearId, 150.GBP, b)
        val rejectTx = rejectMOTProposal(linearId, a)
        rejectTx.verifyRequiredSignatures()
        println("Signed transaction hash: ${rejectTx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(rejectTx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${rejectTx.id}")
            assertEquals(rejectTx.id, txHash)
        }
    }


}