package com.alice.carapp.test.Insurance

import com.alice.carapp.flows.Insurance.*
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.flows.FlowLogic
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

class InsuranceAgreeTest {
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
        listOf(a, b).forEach { it.registerInitiatedFlow(InsuranceIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    fun runFlow(flow: FlowLogic<SignedTransaction>, ap: StartedMockNode): SignedTransaction {
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun draftInsurance(ap: StartedMockNode): SignedTransaction {
        val insurancer = a.info.chooseIdentityAndCert().party
        val owner = b.info.chooseIdentityAndCert().party
        val draft = Insurance(insurancer, owner, vehicle, 100.GBP, "coverage", start, end, ap.info.legalIdentities.first(), StatusEnum.DRAFT)
        val flow = InsuranceDraftFlow(draft)
        return runFlow(flow, ap)
    }

    fun distributeInsurance(tx: SignedTransaction, newPrice: Amount<TokenType>, date1: Date, date2: Date, cov: String, ap: StartedMockNode): SignedTransaction {
        val output = tx.tx.outputs.single().data as Insurance
        val flow = InsuranceDistributeFlow(output.linearId, newPrice, cov, date1, date2)
        return runFlow(flow, ap)
    }

    fun agreeInsurance(tx: SignedTransaction, ap: StartedMockNode): SignedTransaction {
        val output = tx.tx.outputs.single().data as Insurance
        val flow = InsuranceAgreeFlow(output.linearId)
        return runFlow(flow, ap)
    }

    /* the wrong party who started AgreeFlow
        a issue Insurance
        a distribute Insurance
        a agree Insurance
     */
    @Test
    fun testWrongActionParty() {
        val itx = draftInsurance(a)
        val draft = itx.tx.outputStates.single() as Insurance
        val distributeTx = distributeInsurance(itx, draft.price, draft.effectiveDate, draft.expiryDate, draft.coverage, a)

        assertFailsWith<IllegalArgumentException> {agreeInsurance(distributeTx, a)}

    }

    /*
        the wrong input status
        a issue Insurance
        a agree Insurance
     */
    @Test
    fun testInputStatus() {
        val issueTx = draftInsurance(a)
        assertFailsWith<TransactionVerificationException> {agreeInsurance(issueTx, a)}
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val itx = draftInsurance(a)
        val draft = itx.tx.outputStates.single() as Insurance
        val dtx = distributeInsurance(itx, draft.price, draft.effectiveDate, draft.expiryDate, draft.coverage, a)
        val atx = agreeInsurance(dtx, b)
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