package com.alice.carapp.test.MOTProposal

import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.flows.MOTProposal.MOTProposalDistributeFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.IllegalArgumentException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class MOTProposalIssueTest {
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

    // Issue Flow test
    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val owner = a.info.chooseIdentityAndCert().party
        val tester = b.info.chooseIdentityAndCert().party
        val proposal = MOTProposal(tester, owner, vehicle, 1000.GBP, StatusEnum.DRAFT, owner)
        val flow = MOTProposalIssueFlow(proposal)
        val future = a.startFlow(flow)
        mockNetwork.runNetwork()
        // Return the unsigned(!) SignedTransaction object from the IOUIssueFlow.
        val ptx: SignedTransaction = future.getOrThrow()
        // Print the transaction for debugging purposes.
        println(ptx.tx)
        // Check the transaction is well formed...
        // No outputs, one input IOUState and a command with the right properties.
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
        val futureOne = a.startFlow(flow)
        mockNetwork.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureOne.getOrThrow() }
        // Check that proposal with wrong status fails.
        val pendingProposal = MOTProposal(tester, owner, vehicle, 1000.GBP, StatusEnum.PENDING, tester)
        val futureTwo = a.startFlow(MOTProposalIssueFlow(pendingProposal))
        mockNetwork.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureTwo.getOrThrow() }
        // Check that proposal with wrong action party fails.
        val proposalWrongAP = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, owner)
        val futureThree = a.startFlow(MOTProposalIssueFlow(proposalWrongAP))
        mockNetwork.runNetwork()
        assertFailsWith<IllegalArgumentException> { futureThree.getOrThrow() }
        // Check a good proposal passes.
        val proposal = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val futureFour = a.startFlow(MOTProposalIssueFlow(proposal))
        mockNetwork.runNetwork()
        futureFour.getOrThrow()
    }

    @Test
    fun flowReturnsTransactionSignedByBothParties() {
        val tester = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val proposal = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val future = a.startFlow(MOTProposalIssueFlow(proposal))
        mockNetwork.runNetwork()
        val stx = future.getOrThrow()
        stx.verifyRequiredSignatures()
    }

    @Test
    fun flowRecordsTheSameTransactionInBothPartyVaults() {
        val tester = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val proposal = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val future = a.startFlow(MOTProposalIssueFlow(proposal))
        mockNetwork.runNetwork()
        val stx = future.getOrThrow()
        println("Signed transaction hash: ${stx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(stx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${stx.id}")
            assertEquals(stx.id, txHash)
        }
    }

    // It is expected to fail, but in fact it got stuck for minutes
    @Test
    fun issueSameLinearId(){
        val tester = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val proposal1 = MOTProposal(tester, owner, vehicle, 10.GBP, StatusEnum.DRAFT, tester)
        val future1 = a.startFlow(MOTProposalIssueFlow(proposal1))
        mockNetwork.runNetwork()
        val stx1 = future1.getOrThrow()

        val proposal2 = MOTProposal(tester, owner, vehicle, 100.GBP, StatusEnum.DRAFT, tester, proposal1.linearId)
        val future2 = a.startFlow(MOTProposalIssueFlow(proposal2))
        mockNetwork.runNetwork()
        future2.getOrThrow()

        val future3 = a.startFlow(MOTProposalDistributeFlow(proposal1.linearId, 100.GBP))
        mockNetwork.runNetwork()
        assertFails { future3.getOrThrow() }
    }

}