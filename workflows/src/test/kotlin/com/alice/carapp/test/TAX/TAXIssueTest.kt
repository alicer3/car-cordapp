package com.alice.carapp.test.tax


import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.flows.MOT.MOTCancelFlow
import com.alice.carapp.flows.MOT.MOTUpdateFlow
import com.alice.carapp.flows.TAX.TAXIssueFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOT
import com.alice.carapp.states.TAX
import com.alice.carapp.test.BaseTest
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.vault.MAX_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
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

class TAXIssueTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var c: StartedMockNode
    private lateinit var lta: StartedMockNode
    private lateinit var vehicle1: Vehicle
    private lateinit var vehicle2: Vehicle
    private lateinit var date1: Date
    private lateinit var date2: Date
    private lateinit var date3: Date
    private lateinit var date4: Date
    private lateinit var date5: Date
    private lateinit var date6: Date

    @Before
    fun setup() {
        setDates()
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))),
                networkParameters = MockNetworkParameters().networkParameters.copy(minimumPlatformVersion = 4))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        c = mockNetwork.createPartyNode()
        lta = mockNetwork.createPartyNode(legalName = CordaX500Name("LTA", "London", "GB"))
        vehicle1 = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)
        vehicle2 = vehicle1.copy(id = 456)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b, c, lta).forEach { it.registerInitiatedFlow(InsuranceIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    private fun setDates() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, -3)
        date1 = calendar.time
        calendar.add(Calendar.MONTH, 3)
        calendar.add(Calendar.DATE, 1)
        date2 = calendar.time
        calendar.add(Calendar.DATE, 2)
        date3 = calendar.time
        calendar.add(Calendar.MONTH, 13)
        date4 = calendar.time
        calendar.add(Calendar.MONTH, 1)
        date5 = calendar.time
        calendar.add(Calendar.MONTH, 1)
        date6 = calendar.time
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    /*
    input: insurance + mot + cash
    output: tax + cash with new owner

    1. wrong initiating party (only owner)/ wrong responding party (only LTA)
    2. not enough cash
    3. no MOT
    4. MOT with wrong details
        wrong vehicle
        wrong result
        wrong owner
        wrong dates (testDate is not within one year, expiryDate is before the insurance expiryDate)
    5. no insurance
    6. insurance with wrong details
        wrong status
        wrong dates

     */

    @Test
    fun wrongAP() {
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)
        issueCash(TAX.price, a)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow = TAXIssueFlow(tax)
        assertFailsWith<IllegalArgumentException> { runFlow(issueFlow, lta) }

        val tax2 = TAX(LTA = b.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow2 = TAXIssueFlow(tax2)
        assertFailsWith<IllegalArgumentException> { runFlow(issueFlow2, a) }
    }

    @Test
    fun notEnoughCash() {
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow = TAXIssueFlow(tax)
        assertFailsWith<IllegalArgumentException> { runFlow(issueFlow, a) }
    }

    @Test
    fun noMOT() {
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)

        val mot = a.findMOT().single().state.data
        runFlow(MOTCancelFlow(mot.linearId), b)
        issueCash(TAX.price, a)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow = TAXIssueFlow(tax)
        assertFailsWith<IllegalArgumentException> { runFlow(issueFlow, a) }
    }

    @Test
    fun wrongMOTDetail() {
        //wrong owner
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val tax1 = tax.copy(owner = b.info.legalIdentities.first())
        issueCash(TAX.price, a)
        issueCash(TAX.price, b)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax1), b) }

        //wrong vehicle
        val tax2 = tax.copy(vehicle = vehicle2)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax2), a) }

        //wrong result
        val mot1 = a.findMOT().single().state.data
        runFlow(MOTUpdateFlow(mot1.linearId, newResult = false, newTestDate = mot1.testDate, newExpiryDate = mot1.expiryDate), b)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax), a) }

        //wrong test date
        val newCalendar = Calendar.getInstance()
        newCalendar.add(Calendar.YEAR, -2)
        val twoYearAgo = newCalendar.time
        runFlow(MOTUpdateFlow(mot1.linearId, newResult = true, newTestDate = twoYearAgo, newExpiryDate = mot1.expiryDate), b)
        assertFailsWith<TransactionVerificationException> { runFlow(TAXIssueFlow(tax), a) }

        //wrong expiry date
        newCalendar.add(Calendar.YEAR, 3)
        runFlow(MOTUpdateFlow(mot1.linearId, newResult = true, newTestDate = date1, newExpiryDate = newCalendar.time), b)
        assertFailsWith<TransactionVerificationException> { runFlow(TAXIssueFlow(tax), a) }
    }

    @Test
    fun wrongInsuranceDetail1() {
        getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date6, vehicle = vehicle1)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        issueCash(TAX.price, a)
        //no insurance
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax), a) }

        //wrong insurance status
        getAgreedInsurance(a, c, a, c, date2, date5, vehicle1)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax), a) }

    }

    @Test
    fun wrongInsuranceDetail2() {
        issueCash(TAX.price, a)
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val tax1 = tax.copy(effectiveDate = date2)
        assertFailsWith<TransactionVerificationException> { runFlow(TAXIssueFlow(tax1), a) }
    }

    @Test
    fun wrongInsuranceDetail3() {
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val tax2 = tax.copy(expiryDate = date5)
        issueCash(TAX.price, a)
        assertFailsWith<TransactionVerificationException> { runFlow(TAXIssueFlow(tax2), a) }
        runFlow(TAXIssueFlow(tax), a)
    }


    @Test
    fun healthcheck() {
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, motTD = date1, motED = date6, effective = date2, expiry = date5, vehicle_in = vehicle1, vehicle_mot = vehicle1)
        issueCash(TAX.price * 2, a)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)

        val issueFlow = TAXIssueFlow(tax)
        val atx = runFlow(issueFlow, a)
        atx.verifyRequiredSignatures()
        println("Signed transaction hash: ${atx.id}")
        listOf(a, lta).map {
            it.services.validatedTransactions.getTransaction(atx.id)
        }.forEach {
            val txHash = (it as SignedTransaction).id
            println("$txHash == ${atx.id}")
            assertEquals(atx.id, txHash)
        }
    }

    private fun StartedMockNode.findMOT() = transaction {
        services.vaultService.queryBy(
                MOT::class.java,
                QueryCriteria.VaultQueryCriteria(),
                PageSpecification(1, MAX_PAGE_SIZE)
        )
    }.states.filter { it.state.data.owner == this.info.legalIdentities.single() }

}