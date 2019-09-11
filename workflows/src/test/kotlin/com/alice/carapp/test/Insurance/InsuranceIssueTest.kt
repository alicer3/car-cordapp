package com.alice.carapp.test.insurance

import com.alice.carapp.flows.Insurance.InsuranceIssueFlow
import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.test.BaseTest
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.flows.UnexpectedFlowEndException
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InsuranceIssueTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var c: StartedMockNode
    private lateinit var vehicle1: Vehicle
    private lateinit var vehicle2: Vehicle
    private lateinit var date1: Date
    private lateinit var date2: Date
    private lateinit var date3: Date
    private lateinit var date4: Date
    private val calendar = Calendar.getInstance()

    @Before
    fun setup() {
        setDates()
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))),
                networkParameters = MockNetworkParameters().networkParameters.copy(minimumPlatformVersion = 4))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        c = mockNetwork.createPartyNode()
        vehicle1 = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)
        vehicle2 = vehicle1.copy(id = 456)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b, c).forEach { it.registerInitiatedFlow(InsuranceIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    // prepare dates
    private fun setDates() {
        calendar.add(Calendar.MONTH, -2)
        date1 = calendar.time
        calendar.add(Calendar.MONTH, -1)
        date2 = calendar.time
        calendar.add(Calendar.MONTH, 12)
        date3 = calendar.time
        calendar.add(Calendar.MONTH, 13)
        date4 = calendar.time
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }


    /*
    input: agreed insurance + cash
    output: issued insurance + cash with new owner
    reference: MOT
    1. wrong initiating party (only insurancer)
    2. wrong input status
    3. not enough cash
    4. no MOT
    5. MOT with wrong details
        wrong vehicle
        wrong result
        wrong owner
        wrong dates (testDate is not within one year, expiryDate is before the insurance expiryDate)
     */

    @Test
    fun wrongActionParty() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<IllegalArgumentException> { runFlow(issueFlow, a) }
    }

    @Test
    fun wrongInputStatus() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, vehicle = vehicle1)
        val tx = getDistributedInsurance(ownerNode = a, insurancerNode = c, initater = a, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<TransactionVerificationException> { runFlow(issueFlow, c) }
    }

    @Test
    fun notEnoughCash() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<UnexpectedFlowEndException> { runFlow(issueFlow, c) }
    }

    @Test
    fun noMOT() {
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<UnexpectedFlowEndException> { runFlow(issueFlow, c) }
    }

    @Test
    fun wrongOwnerInMot() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = b, insurancerNode = c, initater = b, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, b)
        issueCash(draft.price, b)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<UnexpectedFlowEndException> { runFlow(issueFlow, c) }
    }

    @Test
    fun wrongVehicleInMot() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, vehicle = vehicle2)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<UnexpectedFlowEndException> { runFlow(issueFlow, c) }
    }

    @Test
    fun wrongResultInMot() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, result = false, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<UnexpectedFlowEndException> { runFlow(issueFlow, c) }
    }

    @Test
    fun wrongTestDateInMot() {
        val newCalendar = Calendar.getInstance()
        newCalendar.add(Calendar.YEAR, -2)
        val twoYearAgo = newCalendar.time
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = twoYearAgo, motED = date4, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<TransactionVerificationException> { runFlow(issueFlow, c) }
    }

    @Test
    fun wrongExpiryDateInMot() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date3, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date4, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        assertFailsWith<TransactionVerificationException> { runFlow(issueFlow, c) }
    }

    @Test
    fun healthcheck() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4, vehicle = vehicle1)
        val tx = getAgreedInsurance(ownerNode = a, insurancerNode = c, initater = a, counter = c, effective = date2, expiry = date3, vehicle = vehicle1)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(59.GBP, a)
        issueCash(draft.price, a)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        val atx = runFlow(issueFlow, c)
        atx.verifyRequiredSignatures()
        println("Signed transaction hash: ${atx.id}")
        listOf(a, c).map {
            it.services.validatedTransactions.getTransaction(atx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${atx.id}")
            assertEquals(atx.id, txHash)
        }
    }

}