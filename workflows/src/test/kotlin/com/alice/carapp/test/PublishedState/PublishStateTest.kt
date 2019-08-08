package com.alice.carapp.test.PublishedState

import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.flows.ModeEnum
import com.alice.carapp.flows.PublishStateFlow
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.contracts.DummyState


import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.test.assertFailsWith
import com.alice.carapp.states.MOTProposal
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.contracts.Amount
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishStateTest {
    private lateinit var mockNetwork: MockNetwork
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

    private fun setDates(){
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


    // test publish a state which does not exist in vault
    @Test
    fun publishStateNotExist() {
        val dummyState = DummyState()
        val future = a.startFlow(PublishStateFlow(dummyState))
        mockNetwork.runNetwork()
        assertFailsWith<IllegalArgumentException> { future.getOrThrow() }
    }

    // test ISSUENEW mode
    @Test
    fun publishWithIssueNewMode(){
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        val future1 = a.startFlow(PublishStateFlow(mot, mode = ModeEnum.NEWISSUE))
        mockNetwork.runNetwork()
        val published1 = future1.getOrThrow()
        assertTrue { published1.state.data.data == mot }
        assertTrue { published1.state.data.owner == a.info.legalIdentities.first() }

        val future2 = a.startFlow(PublishStateFlow(mot, mode = ModeEnum.NEWISSUE))
        mockNetwork.runNetwork()
        val published2 = future2.getOrThrow()
        assertFalse { published1 == published2 }
    }

    // test REUSE mode
    @Test
    fun publishWithReuseMode(){
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        val future1 = a.startFlow(PublishStateFlow(mot, mode = ModeEnum.REUSE))
        mockNetwork.runNetwork()
        val published1 = future1.getOrThrow()
        assertTrue { published1.state.data.data == mot }
        assertTrue { published1.state.data.owner == a.info.legalIdentities.first() }

        val future2 = a.startFlow(PublishStateFlow(mot, mode = ModeEnum.REUSE))
        mockNetwork.runNetwork()
        val published2 = future2.getOrThrow()
        assertTrue { published1.ref == published2.ref }
        assertTrue { published1 == published2 }
    }

    // published by different owner
    @Test
    fun publishByDifferentOwner(){
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        val future1 = a.startFlow(PublishStateFlow(mot, mode = ModeEnum.NEWISSUE))
        mockNetwork.runNetwork()
        val published1 = future1.getOrThrow()

        val future2 = b.startFlow(PublishStateFlow(mot, mode = ModeEnum.NEWISSUE))
        mockNetwork.runNetwork()
        val published2 = future2.getOrThrow()
        assertFalse { published1 == published2 }
        assertFalse { published1.state.data == published2.state.data }
        assertTrue { published1.state.data.data == published2.state.data.data }
    }



    fun getIssuedMOT(ownerNode: StartedMockNode, testerNode: StartedMockNode, initater: StartedMockNode, counter: StartedMockNode, motTD: Date, motED: Date, vehicle: Vehicle = vehicle1, result: Boolean = true): SignedTransaction {
        val tester = testerNode.info.legalIdentities.single()
        val owner = ownerNode.info.legalIdentities.single()
        val proposal = MOTProposal(tester = tester, owner = owner, vehicle = vehicle, price = 100.GBP, status = StatusEnum.DRAFT, actionParty = initater.info.legalIdentities.first())
        val issueFlow = MOTProposalIssueFlow(proposal)
        runFlow(issueFlow, initater)
        val distributeFlow = MOTProposalDistributeFlow(proposal.linearId, 100.GBP)
        runFlow(distributeFlow, initater)
        val agreeFlow = MOTProposalAgreeFlow(proposal.linearId)
        runFlow(agreeFlow, counter)
        val payFlow = MOTProposalPayFlow(proposal.linearId)
        issueCash(100.GBP, ownerNode)
        runFlow(payFlow, ownerNode)
        val issueMOTFlow = MOTIssueFlow(proposal.linearId, testDate = motTD, expiryDate = motED, loc = "loc", result = result)
        return runFlow(issueMOTFlow, testerNode)
    }


    private fun issueCash(amount: Amount<TokenType>, ap: StartedMockNode): Unit {
        val flow = SelfIssueCashFlow(amount, ap.info.legalIdentities.first())
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    private fun runFlow(flow: FlowLogic<SignedTransaction>, ap: StartedMockNode): SignedTransaction {
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }
//
//    /*
//    1. no MOT
//    2. tester issue
//    3. health check
//     */
//
//    @Test
//    fun noMOTtoCopy() {
//        val mot = MOT(date1, date3, "loc",   a.info.legalIdentities.first(),vehicle1, b.info.legalIdentities.first(), true)
//        assertFailsWith<IllegalArgumentException> { runFlow(PublishStateFlow(mot), b) }
//    }
//
//
//    @Test
//    fun healthCheck() {
//        val itx = getIssuedMOT(a, b, a, b, date1, date3)
//        val mot = itx.tx.outputsOfType<MOT>().single()
//        val tx = runFlow(PublishStateFlow(mot), a)
//        tx.verifyRequiredSignatures()
//        listOf(a, b).map {
//            it.services.validatedTransactions.getTransaction(tx.id)
//        }.forEach {
//            val txHash = (it as SignedTransaction).id
//            println("$txHash == ${tx.id}")
//            assertEquals(tx.id, txHash)
//
//        }
//
//   }
}