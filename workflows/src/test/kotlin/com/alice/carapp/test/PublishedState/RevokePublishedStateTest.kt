package com.alice.carapp.test.publishedstate


import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.flows.ModeEnum
import com.alice.carapp.flows.PublishStateFlow
import com.alice.carapp.flows.RevokePublishedStateFlow
import com.alice.carapp.helper.PublishedState
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.test.BaseTest
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.vault.MAX_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals

class RevokePublishedStateTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var c: StartedMockNode
    private lateinit var date1: Date
    private lateinit var date2: Date
    private lateinit var date3: Date
    private lateinit var date4: Date

    @Before
    fun setup() {
        setDates()
        mockNetwork = MockNetwork(listOf("com.alice.carapp", "com.r3.corda.lib.token.money", "com.r3.corda.lib.tokens.contracts"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))),
                networkParameters = MockNetworkParameters().networkParameters.copy(minimumPlatformVersion = 4))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        c = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b, c).forEach { it.registerInitiatedFlow(InsuranceIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    private fun setDates() {
        val calendar = Calendar.getInstance()
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
    fun revokeSomethingUnPublished() {
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        assertEquals(0, a.countPublishedMOT())
        val future = a.startFlow(RevokePublishedStateFlow(mot))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(0, a.countPublishedMOT())
    }

    @Test
    fun revokeSingleStateMultiplePublish() {
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
        val mot = issueTx.tx.outputStates.single()
        repeat(3) { publish(mot, a) }
        assertEquals(3, a.countPublishedMOT())
        val future = a.startFlow(RevokePublishedStateFlow(mot))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(0, a.countPublishedMOT())
    }

    @Test
    fun revokeMultipleStateMultiplePublish() {
        val issueTx1 = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
        val mot1 = issueTx1.tx.outputStates.single()
        repeat(3) { publish(mot1, a) }
        assertEquals(3, a.countPublishedMOT())

        val issueTx2 = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date2, motED = date4)
        val mot2 = issueTx2.tx.outputStates.single()
        repeat(3) { publish(mot2, a) }
        assertEquals(6, a.countPublishedMOT())


        val future = a.startFlow(RevokePublishedStateFlow(mot1))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(3, a.countPublishedMOT())

    }

    @Test
    fun revokeStatePublishedByDifferentParties() {
        val issueTx1 = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
        val mot1 = issueTx1.tx.outputStates.single()
        repeat(3) { publish(mot1, a) }
        repeat(3) { publish(mot1, b) }
        assertEquals(3, a.countPublishedMOT())
        assertEquals(3, b.countPublishedMOT())

        val future = a.startFlow(RevokePublishedStateFlow(mot1))
        mockNetwork.runNetwork()
        future.getOrThrow()
        assertEquals(0, a.countPublishedMOT())
        assertEquals(0, b.countPublishedMOT())

    }


    private fun publish(state: ContractState, ap: StartedMockNode): StateAndRef<PublishedState<ContractState>> {
        val future = ap.startFlow(PublishStateFlow(state, mode = ModeEnum.NEWISSUE))
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