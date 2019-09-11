package com.alice.carapp.test.motproposal

import com.alice.carapp.contracts.MOTProposalContract
import com.alice.carapp.flows.MOTProposal.MOTProposalDistributeFlow
import com.alice.carapp.flows.MOTProposal.MOTProposalIssueFlowResponder
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.test.BaseTest
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MOTProposalDistributeTest : BaseTest() {
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode

    @Before
    fun setup() {
        mockNetwork = MockNetwork(listOf("com.alice.carapp"),
                notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))))
        a = mockNetwork.createPartyNode()
        b = mockNetwork.createPartyNode()
        vehicle = Vehicle(123, "registrationABC", "SG", "model", "cate", 123123)

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(MOTProposalIssueFlowResponder::class.java) }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    @Test
    fun flowReturnsCorrectlyFormedPartiallySignedTransaction() {
        val itx = getIssuedProposal(a, b, a)
        val output = itx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalDistributeFlow(output.linearId, output.price.minus(10.GBP))
        val ptx = runFlow(flow, a)

        // Check the transaction is well formed...
        // One output MOTProposal, one input state reference and a Distribute command with the right properties.
        assert(ptx.tx.inputs.size == 1)
        assert(ptx.tx.outputs.size == 1)
        assert(ptx.tx.inputs.single() == StateRef(itx.id, 0))
        println("Input state ref: ${ptx.tx.inputs.single()} == ${StateRef(itx.id, 0)}")

        println("Output state: ${ptx.tx.outputs.single()}")
        val command = ptx.tx.commands.single()
        assert(command.value is MOTProposalContract.Commands.Distribute)
        ptx.verifySignaturesExcept(b.info.chooseIdentityAndCert().party.owningKey, a.info.chooseIdentityAndCert().party.owningKey,
                mockNetwork.defaultNotaryNode.info.legalIdentitiesAndCerts.first().owningKey)
    }

    //  a issue MOTProposal
    //  a distribute MOTProposal to b (ap: b)
    //  b agree the MOTProposal (ap: b)
    //  b distribute the MOTProposal again
    @Test
    fun flowCheckInputStatus() {
        val atx = getAgreedProposal(a, b, a, b)
        val output = atx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalDistributeFlow(output.linearId, output.price.minus(10.GBP))
        assertFailsWith<TransactionVerificationException> { runFlow(flow, b) }

    }

    //    a issue MOTProposal
    //    a distribute MOTProposal
    //    a distribute MOTProposal again
    @Test
    fun flowRunByRightParty() {
        val dtx = getDistributedProposal(a, b, a)
        val output = dtx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalDistributeFlow(output.linearId, output.price.minus(10.GBP))
        assertFailsWith<IllegalArgumentException> { runFlow(flow, a) }
    }


    // health check
    @Test
    fun flowReturnsTransactionSignedByAllPartiesAndCheckVaults() {
        val dtx = getDistributedProposal(a, b, a)
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