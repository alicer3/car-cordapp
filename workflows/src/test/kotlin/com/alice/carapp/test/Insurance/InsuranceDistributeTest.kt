package com.alice.carapp.test.Insurance

import com.alice.carapp.contracts.InsuranceContract
import com.alice.carapp.flows.Insurance.InsuranceDistributeFlow
import com.alice.carapp.flows.Insurance.InsuranceDistributeFlowResponder
import com.alice.carapp.flows.Insurance.InsuranceDraftFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateRef
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
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InsuranceDistributeTest {
    private lateinit var mockNetwork: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var vehicle: Vehicle
    val start = Date(2019,6,1)
    val end = Date(2020, 5, 31)

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(InsuranceDistributeFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    fun draftInsurance(ap: StartedMockNode): SignedTransaction {
        val insurancer = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val draft = Insurance(insurancer, owner, vehicle, 100.GBP, "coverage", start, end, ap.info.legalIdentities.first(), StatusEnum.DRAFT)
        val flow = InsuranceDraftFlow(draft)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }


    fun distributeInsurance(tx: SignedTransaction, newPrice: Amount<FiatCurrency>, date1: Date, date2: Date, cov: String, ap: StartedMockNode): SignedTransaction {
        val output = tx.tx.outputs.single().data as Insurance
        val flow = InsuranceDistributeFlow(output.linearId, newPrice, cov, date1, date2)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val itx = draftInsurance(b)
        val draft = itx.tx.outputStates.single() as Insurance
        val ptx = distributeInsurance(itx, draft.price.minus(10.GBP), draft.effectiveDate, draft.expiryDate, draft.coverage, b)
        // Check the transaction is well formed...
        // One output IOUState, one input state reference and a Transfer command with the right properties.
        assert(ptx.tx.inputs.size == 1)
        assert(ptx.tx.outputs.size == 1)
        assert(ptx.tx.inputs.single() == StateRef(itx.id, 0))
        println("Input state ref: ${ptx.tx.inputs.single()} == ${StateRef(itx.id, 0)}")
        //val output = ptx.tx.outputs.single().data as Insurance
        println("Output state: ${ptx.tx.outputs.single()}")
        val command = ptx.tx.commands.single()
        assert(command.value is InsuranceContract.Commands.Distribute)
        ptx.verifyRequiredSignatures()
    }

//    @Test
//    fun flowCheckInputStatus() {
//        val itx = draftInsurance(b)
//        val draft = itx.tx.outputStates.single() as Insurance
//        val dtx = distributeInsurance(itx, draft.price, draft.effectiveDate, draft.expiryDate, draft.coverage, b)
//        //val atx = agreeInsurance(dtx, b)
//
//        assertFailsWith<TransactionVerificationException> { distributeInsurance(atx, 150.GBP, b) }
//
//    }

    //    a issue Insurance
//    a distribute Insurance
//    a distribute Insurance again
    @Test
    fun flowRunByRightParty() {
        val itx = draftInsurance(b)
        val draft = itx.tx.outputStates.single() as Insurance
        val dtx = distributeInsurance(itx, draft.price, draft.effectiveDate, draft.expiryDate, draft.coverage, b)

        assertFailsWith<IllegalArgumentException> { distributeInsurance(dtx, draft.price.minus(10.GBP), draft.effectiveDate, draft.expiryDate, draft.coverage, b) }
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val itx = draftInsurance(b)
        val draft = itx.tx.outputStates.single() as Insurance
        val dtx0 = distributeInsurance(itx, draft.price, draft.effectiveDate, draft.expiryDate, draft.coverage, b)
        val dtx = distributeInsurance(dtx0, draft.price.minus(10.GBP), draft.effectiveDate, draft.expiryDate, draft.coverage, a)
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