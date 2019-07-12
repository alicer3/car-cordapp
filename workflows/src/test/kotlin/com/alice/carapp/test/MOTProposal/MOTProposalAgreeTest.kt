package com.alice.carapp.test.MOTProposal

import com.alice.carapp.flows.MOTProposal.MOTProposalAgreeFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalDistributeFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
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
import kotlin.test.assertFailsWith

class MOTProposalAgreeTest {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var vehicle: Vehicle

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

    fun issueProposal(ap: StartedMockNode): SignedTransaction {
        val proposal = MOTProposal(a.info.legalIdentities.first(), b.info.legalIdentities.first(), vehicle, 100.GBP, StatusEnum.DRAFT, ap.info.legalIdentities.first())
        val flow = MOTProposalIssueFlow(proposal)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun distributeMOTProposal(tx: SignedTransaction, newPrice: Amount<TokenType>, ap: StartedMockNode): SignedTransaction {
        val output = tx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalDistributeFlow(output.linearId, newPrice)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun agreeMOTProposal(tx: SignedTransaction, ap: StartedMockNode): SignedTransaction {
        val output = tx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalAgreeFlow(output.linearId)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    /* the wrong party who started AgreeFlow
        a issue MOTProposal
        a distribute MOTProposal
        a agree MOTProposal
     */
    @Test
    fun testWrongActionParty() {
        val issueTx = issueProposal(a)
        val distributeTx = distributeMOTProposal(issueTx, 100.GBP, a)
        assertFailsWith<IllegalArgumentException> {agreeMOTProposal(distributeTx, a)}

    }

    /*
        the wrong input status
        a issue MOTProposal
        a agree MOTProposal
     */
    @Test
    fun testInputStatus() {
        val issueTx = issueProposal(a)
        assertFailsWith<TransactionVerificationException> {agreeMOTProposal(issueTx, a)}
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val itx = issueProposal(a)
        val dtx = distributeMOTProposal(itx, 100.GBP, a)
        val atx = agreeMOTProposal(dtx, b)
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