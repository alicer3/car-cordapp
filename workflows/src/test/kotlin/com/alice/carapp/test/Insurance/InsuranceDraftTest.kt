package com.alice.carapp.test.insurance

import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.flows.Insurance.InsuranceDraftFlow
import com.alice.carapp.flows.Insurance.InsuranceDraftFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.StatusEnum
import com.alice.carapp.test.BaseTest
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith


class InsuranceDraftTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var start: Date
    private lateinit var end: Date


    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
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
        listOf(a, b).forEach { it.registerInitiatedFlow(InsuranceDraftFlowResponder::class.java) }

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
        val insurancer = b.info.chooseIdentityAndCert().party
        val draft = Insurance(insurancer, owner, vehicle, 1000.GBP, "coverage", start, end, owner, StatusEnum.DRAFT)
        val flow = InsuranceDraftFlow(draft)
        val ptx = runFlow(flow, a)
        // Print the transaction for debugging purposes.
        println(ptx.tx)

        // Check the transaction is well formed...
        // No outputs, one input IOUState and a command with the right properties.
        assert(ptx.tx.inputs.isEmpty())
        assert(ptx.tx.outputs.single().data is Insurance)
        val command = ptx.tx.commands.single()
        assert(command.value is InsuranceContract.Commands.Draft)
        assert(command.signers.toSet() == listOf(owner.owningKey).toSet())
        ptx.verifyRequiredSignatures()
    }

    /*
    test insurance details
        wrong status
        price = 0
        wrong dates
        wrong action party
     */
    @Test
    fun test() {
        // Check that a zero amount proposal fails.
        val insurancer = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val zeroDraft = Insurance(insurancer, owner, vehicle, 0.GBP, "coverage", start, end, insurancer, StatusEnum.DRAFT)
        val flow = InsuranceDraftFlow(zeroDraft)
        val futureOne = a.startFlow(flow)
        mockNetwork.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureOne.getOrThrow() }

        // Check that proposal with wrong status fails.
        val pendingProposal = Insurance(insurancer, owner, vehicle, 1000.GBP, "coverage", start, end, insurancer, StatusEnum.PENDING)
        val futureTwo = a.startFlow(InsuranceDraftFlow(pendingProposal))
        mockNetwork.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureTwo.getOrThrow() }

        // Check that proposal with wrong action party fails.
        val proposalWrongAP = Insurance(insurancer, owner, vehicle, 10.GBP, "coverage", start, end, owner, StatusEnum.DRAFT)
        val futureThree = a.startFlow(InsuranceDraftFlow(proposalWrongAP))
        mockNetwork.runNetwork()
        assertFailsWith<IllegalArgumentException> { futureThree.getOrThrow() }

        // Check that proposal with wrong dates fails.
        val wrongProposal = Insurance(insurancer, owner, vehicle, 1000.GBP, "coverage", end, start, insurancer, StatusEnum.DRAFT)
        val futureFive = a.startFlow(InsuranceDraftFlow(wrongProposal))
        mockNetwork.runNetwork()
        assertFailsWith<TransactionVerificationException> { futureFive.getOrThrow() }

        // Check a good proposal passes.
        val proposal = Insurance(insurancer, owner, vehicle, 10.GBP, "coverage", start, end, insurancer, StatusEnum.DRAFT)
        val futureFour = a.startFlow(InsuranceDraftFlow(proposal))
        mockNetwork.runNetwork()
        futureFour.getOrThrow()
    }


    @Test
    fun healthcheck() {
        val insurancer = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val proposal = Insurance(insurancer, owner, vehicle, 10.GBP, "coverage", start, end, insurancer, StatusEnum.DRAFT)
        val stx = runFlow(InsuranceDraftFlow(proposal), a)
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

