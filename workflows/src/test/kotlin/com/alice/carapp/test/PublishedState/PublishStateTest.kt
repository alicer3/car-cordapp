package com.alice.carapp.test.publishedstate


import com.alice.carapp.flows.Insurance.InsuranceIssueFlowResponder
import com.alice.carapp.flows.ModeEnum
import com.alice.carapp.flows.PublishStateFlow
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.test.BaseTest
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.contracts.DummyState
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishStateTest : BaseTest() {
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
    fun publishWithIssueNewMode() {
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
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
    fun publishWithReuseMode() {
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
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
    fun publishByDifferentOwner() {
        val issueTx = getIssuedMOT(ownerNode = a, testerNode = b, motTD = date1, motED = date4)
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

}