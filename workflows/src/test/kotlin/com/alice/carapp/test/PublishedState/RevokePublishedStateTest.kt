package com.alice.carapp.test.PublishedState

import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.flows.ModeEnum
import com.alice.carapp.flows.PublishStateFlow
import com.alice.carapp.flows.RevokePublishedStateFlow
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOT
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
import net.corda.core.contracts.ContractState
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.MAX_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.testing.node.internal.TestStartedNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevokePublishedStateTest {
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

    @Test
    fun revokeSomethingUnPublished(){
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        assertEquals(0, a.countPublishedMOT())
        val future = a.startFlow(RevokePublishedStateFlow(mot))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(0, a.countPublishedMOT())
    }

    @Test
    fun revokeSingleStateMultiplePublish(){
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        repeat(3) { publish(mot, a) }
        assertEquals(3,  a.countPublishedMOT())
        val future = a.startFlow(RevokePublishedStateFlow(mot))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(0, a.countPublishedMOT())
    }

    @Test
    fun revokeMultipleStateMultiplePublish(){
        val issueTx1 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot1 = issueTx1.tx.outputStates.single()
        repeat(3) { publish(mot1, a) }
        assertEquals(3, a.countPublishedMOT())

        val issueTx2 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date2, motED = date4)
        val mot2 = issueTx2.tx.outputStates.single()
        repeat(3) { publish(mot2, a) }
        assertEquals(6, a.countPublishedMOT())


        val future = a.startFlow(RevokePublishedStateFlow(mot1))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(3, a.countPublishedMOT())

    }

    @Test
    fun revokeStatePublishedByDifferentParties(){
        val issueTx1 = getIssuedMOT(ownerNode = a, testerNode = b, initater = a, counter = b, motTD = date1, motED = date4)
        val mot1 = issueTx1.tx.outputStates.single()
        repeat(3) { publish(mot1, a) }
        repeat(3) { publish(mot1, b)}
        assertEquals(3, a.countPublishedMOT())
        assertEquals(3, b.countPublishedMOT())

        val future = a.startFlow(RevokePublishedStateFlow(mot1))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(0, a.countPublishedMOT())
        assertEquals(0, b.countPublishedMOT())

    }


    fun publish(state: ContractState, ap: StartedMockNode): StateAndRef<PublishedState<ContractState>> {
        val future = ap.startFlow(PublishStateFlow(state, mode = ModeEnum.NEWISSUE))
        mockNetwork.runNetwork()
        return future.getOrThrow()
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



    private fun StartedMockNode.countPublishedMOT() = transaction {
        services.vaultService.queryBy(
                PublishedState::class.java,
                QueryCriteria.VaultQueryCriteria(),
                PageSpecification(1, MAX_PAGE_SIZE)
        )
    }.totalStatesAvailable.toInt()
}