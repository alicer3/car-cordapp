package com.alice.carapp.test.TAX

import com.alice.carapp.flows.Insurance.*
import com.alice.carapp.flows.MOT.MOTCancelFlow
import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOT.MOTUpdateFlow
import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.flows.TAX.TAXIssueFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.*
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.UnexpectedFlowEndException
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.packageName
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.schemas.CashSchemaV1
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.IllegalArgumentException
import java.time.LocalDate
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TAXIssueTest {
    private lateinit var mockNetwork: MockNetwork
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
    private val calendar = Calendar.getInstance()

    @Before
    fun setup() {
        setDates()
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "net.corda.finance.contracts.asset", CashSchemaV1::class.packageName),
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

    private fun setDates(){
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

    fun runFlow(flow: FlowLogic<SignedTransaction>, ap: StartedMockNode): SignedTransaction {
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun getAgreedInsurance(ownerNode: StartedMockNode, insurancerNode: StartedMockNode, initater: StartedMockNode, counter: StartedMockNode, effective: Date, expiry: Date, vehicle: Vehicle = vehicle1): SignedTransaction {
        val insurancer = insurancerNode.info.legalIdentities.single()
        val insured = ownerNode.info.legalIdentities.single()
        val draft = Insurance(insurancer, insured, vehicle, 100.POUNDS, "coverage", effective, expiry, initater.info.legalIdentities.first(), StatusEnum.DRAFT)

        val itx = runFlow(InsuranceDraftFlow(draft), initater)
        val distributeFlow = InsuranceDistributeFlow(draft.linearId, draft.price, draft.coverage, effective, expiry)
        val distributeTx = runFlow(distributeFlow, initater)
        val agreeFlow = InsuranceAgreeFlow(draft.linearId)
        return runFlow(agreeFlow, counter)
    }

    fun getIssuedInsurance(ownerNode: StartedMockNode, testerNode: StartedMockNode, insurancerNode: StartedMockNode, insuED: Date, insuExD: Date,  vehicle: Vehicle = vehicle1): SignedTransaction {
        //getIssuedMOT(ownerNode, testerNode, initater = ownerNode, counter = testerNode, motTD = motTD, motED = motED)
        val tx = getAgreedInsurance(ownerNode = ownerNode, insurancerNode = insurancerNode, initater = ownerNode, counter = insurancerNode, effective = insuED, expiry = insuExD)
        val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(draft.price, ownerNode)
        val issueFlow = InsuranceIssueFlow(draft.linearId)
        return runFlow(issueFlow, insurancerNode)
    }


    fun getIssuedMOT(ownerNode: StartedMockNode, testerNode: StartedMockNode, initater: StartedMockNode, counter: StartedMockNode, motTD: Date, motED: Date, vehicle: Vehicle = vehicle1, result: Boolean = true): SignedTransaction {
        val tester = testerNode.info.legalIdentities.single()
        val owner = ownerNode.info.legalIdentities.single()
        val proposal = MOTProposal(tester, owner, vehicle, 100.POUNDS, StatusEnum.DRAFT, initater.info.legalIdentities.first())
        val issueFlow = MOTProposalIssueFlow(proposal)
        runFlow(issueFlow, initater)
        val distributeFlow = MOTProposalDistributeFlow(proposal.linearId, 100.POUNDS)
        runFlow(distributeFlow, initater)
        val agreeFlow = MOTProposalAgreeFlow(proposal.linearId)
        runFlow(agreeFlow, counter)
        val payFlow = MOTProposalPayFlow(proposal.linearId)
        issueCash(100.POUNDS, ownerNode)
        runFlow(payFlow, ownerNode)
        val issueMOTFlow = MOTIssueFlow(proposal.linearId, testDate = motTD, expiryDate = motED, loc = "loc", result = result)
        return runFlow(issueMOTFlow, testerNode)
    }

    fun cancelMOT(mot: MOT, ap: StartedMockNode) {
        val cancelFlow = MOTCancelFlow(mot.linearId)
        runFlow(cancelFlow, ap)
    }


    private fun issueCash(amount: Amount<Currency>, ap: StartedMockNode): Cash.State {
        val flow = SelfIssueCashFlow(amount)
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
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
    fun wrongAP(){
        getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6)
        val tx = getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date2, insuExD = date5)
        //val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(TAX.price, a)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow = TAXIssueFlow(tax)
        assertFailsWith<IllegalArgumentException> {  runFlow(issueFlow, lta) }

        val tax2 = TAX(LTA = b.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow2= TAXIssueFlow(tax2)
        assertFailsWith<IllegalArgumentException> {  runFlow(issueFlow2, a) }
    }

    @Test
    fun notEnoughCash() {
        getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6)
        val tx = getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date2, insuExD = date5)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow = TAXIssueFlow(tax)
        assertFailsWith<IllegalArgumentException> {  runFlow(issueFlow, a) }
    }

    @Test
    fun noMOT() {
        val tx = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6)
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date2, insuExD = date5)
        cancelMOT(tx.tx.outputsOfType<MOT>().single(), b)
        issueCash(TAX.price, a)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val issueFlow = TAXIssueFlow(tax)
        assertFailsWith<IllegalArgumentException> {  runFlow(issueFlow, a) }
    }

    @Test
    fun wrongMOTDetail(){
        //wrong owner
        val mot1 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6).tx.outputsOfType<MOT>().single()
        val insurance1 = getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date2, insuExD = date5)
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        val tax1 = tax.copy(owner = b.info.legalIdentities.first())
        issueCash(TAX.price, a)
        issueCash(TAX.price, b)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax1), b) }
        //wrong vehicle
        val tax2 = tax.copy(vehicle = vehicle2)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax2), a) }
        //wrong result
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
    fun wrongInsuranceDetail(){
        val mot1 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6).tx.outputsOfType<MOT>().single()
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        issueCash(TAX.price, a)
        //no insurance
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax), a) }

        //wrong insurance status
        val insurance1 = getAgreedInsurance(a, c, a, c, date2, date5)
        assertFailsWith<IllegalArgumentException> { runFlow(TAXIssueFlow(tax), a) }

    }

    @Test
    fun wrongInsuranceDetail1(){
        val mot1 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6).tx.outputsOfType<MOT>().single()
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        issueCash(TAX.price, a)
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date3, insuExD = date5)
        val tax1 = tax.copy(effectiveDate = date2)
        assertFailsWith<TransactionVerificationException> { runFlow(TAXIssueFlow(tax1), a) }
    }

    @Test
    fun wrongInsuranceDetail2(){
        val mot1 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6).tx.outputsOfType<MOT>().single()
        val tax = TAX(LTA = lta.info.legalIdentities.single(), vehicle = vehicle1, owner = a.info.legalIdentities.single(), effectiveDate = date3, expiryDate = date4)
        issueCash(TAX.price, a)
        getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date2, insuExD = date4)
        val tax2 = tax.copy(expiryDate = date5)
        assertFailsWith<TransactionVerificationException> { runFlow(TAXIssueFlow(tax2), a) }
    }


    @Test
    fun  healthcheck(){
        getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date6)
        val tx = getIssuedInsurance(ownerNode = a, insurancerNode = c, testerNode = b, insuED = date2, insuExD = date5)
        //val draft = tx.tx.outputsOfType<Insurance>().single()
        issueCash(TAX.price, a)
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

}