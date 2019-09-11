package com.alice.carapp.test.motproposal

import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.alice.carapp.test.BaseTest
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MOTProposalIssueTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode

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

    // Issue Flow test
    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val owner = a.info.chooseIdentityAndCert().party
        val tester = b.info.chooseIdentityAndCert().party
        val proposal = MOTProposal(tester, owner, vehicle, 1000.GBP, StatusEnum.DRAFT, owner)
        val flow = MOTProposalIssueFlow(proposal)
        val ptx: SignedTransaction = runFlow(flow, a)

        // Print the transaction for debugging purposes.
        println(ptx.tx)

        // Check the transaction is well formed...
        // No inputs, one output MOTProposal and a command with the right properties.
        assert(ptx.tx.inputs.isEmpty())
        assert(ptx.tx.outputs.single().data is MOTProposal)
        val command = ptx.tx.commands.single()
        assert(command.value is MOTProposalContract.Commands.Draft)
        assert(command.signers.toSet() == listOf(owner.owningKey).toSet())
        ptx.verifySignaturesExcept(tester.owningKey,
                mockNetwork.defaultNotaryNode.info.legalIdentitiesAndCerts.first().owningKey)
    }

    @Test
    fun flowReturnsVerifiedPartiallySignedTransaction() {

        // Check that a zero amount proposal fails.
        val tester = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val zeroProposal = MOTProposal(tester, owner, vehicle, 0.GBP, StatusEnum.DRAFT, tester)
        val flow = MOTProposalIssueFlow(zeroProposal)
        assertFailsWith<TransactionVerificationException> { runFlow(flow, a) }

        // Check that proposal with wrong status fails.
        val pendingProposal = MOTProposal(tester, owner, vehicle, 1000.GBP, StatusEnum.PENDING, tester)
        val flow2 = MOTProposalIssueFlow(pendingProposal)
        assertFailsWith<TransactionVerificationException> { runFlow(flow2, a) }

        // Check that proposal with wrong action party fails.
        val proposalWrongAP = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, owner)
        val flow3 = MOTProposalIssueFlow(proposalWrongAP)
        assertFailsWith<IllegalArgumentException> { runFlow(flow3, a) }

        // Check a good proposal passes.
        val proposal = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val flow4 = MOTProposalIssueFlow(proposal)
        runFlow(flow4, a)
    }

    @Test
    fun flowReturnsTransactionSignedByBothParties() {
        val tester = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val proposal = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val flow = MOTProposalIssueFlow(proposal)
        val stx = runFlow(flow, a)
        stx.verifyRequiredSignatures()
    }

    @Test
    fun flowRecordsTheSameTransactionInBothPartyVaults() {
        val tester = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val proposal = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val flow = MOTProposalIssueFlow(proposal)
        val stx = runFlow(flow, a)
        println("Signed transaction hash: ${stx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(stx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${stx.id}")
            assertEquals(stx.id, txHash)
        }
    }

}