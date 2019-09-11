package com.alice.carapp.test.insurance

import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.flows.Insurance.InsuranceDistributeFlow
import com.alice.carapp.flows.Insurance.InsuranceDistributeFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
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

class InsuranceDistributeTest : BaseTest() {
    //private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    //private lateinit var vehicle: Vehicle
    private lateinit var start: Date
    private lateinit var end: Date

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp"),
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
        listOf(a, b).forEach { it.registerInitiatedFlow(InsuranceDistributeFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

//    private fun draftInsurance(ap: StartedMockNode): SignedTransaction {
//        val insurancer = a.info.chooseIdentityAndCert().party
//        val owner = b.info.chooseIdentityAndCert().party
//        val draft = Insurance(insurancer, owner, vehicle, 100.GBP, "coverage", start, end, ap.info.legalIdentities.first(), StatusEnum.DRAFT)
//        val flow = InsuranceDraftFlow(draft)
//        val future = ap.startFlow(flow)
//        mockNetwork.runNetwork()
//        return future.getOrThrow()
//    }
//
//
//    private fun distributeInsurance(tx: SignedTransaction, newPrice: Amount<TokenType>, date1: Date, date2: Date, cov: String, ap: StartedMockNode): SignedTransaction {
//        val output = tx.tx.outputs.single().data as Insurance
//        val flow = InsuranceDistributeFlow(output.linearId, newPrice, cov, date1, date2)
//        val future = ap.startFlow(flow)
//        mockNetwork.runNetwork()
//        return future.getOrThrow()
//    }

    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val itx = getDraftInsurance(a, b, a, start, end, vehicle)
        val draft = itx.tx.outputStates.single() as Insurance
        val flow = InsuranceDistributeFlow(draft.linearId, draft.price, draft.coverage, draft.effectiveDate, draft.expiryDate)
        val ptx = runFlow(flow, a)

        assert(ptx.tx.inputs.size == 1)
        assert(ptx.tx.outputs.size == 1)
        assert(ptx.tx.inputs.single() == StateRef(itx.id, 0))

        println("Input state ref: ${ptx.tx.inputs.single()} == ${StateRef(itx.id, 0)}")
        println("Output state: ${ptx.tx.outputs.single()}")

        val command = ptx.tx.commands.single()
        assert(command.value is InsuranceContract.Commands.Distribute)
        ptx.verifyRequiredSignatures()
    }

    /*
        a issue Insurance
        a distribute Insurance
        a distribute Insurance again
     */
    @Test
    fun flowRunByRightParty() {
        val dtx = getDistributedInsurance(a, b, a, start, end, vehicle)
        val insurance = dtx.tx.outputStates.single() as Insurance
        val flow = InsuranceDistributeFlow(insurance.linearId, insurance.price, insurance.coverage, insurance.effectiveDate, insurance.expiryDate)
        assertFailsWith<IllegalArgumentException> { runFlow(flow, a) }
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val dtx0 = getDistributedInsurance(a, b, a, start, end, vehicle)
        val insurance = dtx0.tx.outputStates.single() as Insurance
        val flow = InsuranceDistributeFlow(insurance.linearId, insurance.price, insurance.coverage, insurance.effectiveDate, insurance.expiryDate)
        val dtx = runFlow(flow, b)
        dtx.verifyRequiredSignatures()
        println("Signed transaction hash: ${dtx.id}")
        listOf(a, b).map {
            it.services.validatedTransactions.getTransaction(dtx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${dtx.id}")
            assertEquals(dtx.id, txHash)
        }
    }
}