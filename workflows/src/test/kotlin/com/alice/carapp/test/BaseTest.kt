package com.alice.carapp.test

import com.alice.carapp.flows.Insurance.InsuranceAgreeFlow
import com.alice.carapp.flows.Insurance.InsuranceDistributeFlow
import com.alice.carapp.flows.Insurance.InsuranceDraftFlow
import com.alice.carapp.flows.Insurance.InsuranceIssueFlow
import com.alice.carapp.flows.MOT.MOTIssueFlow
import com.alice.carapp.flows.MOTProposal.*
import com.alice.carapp.helper.Vehicle
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.GBP
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import java.util.*

abstract class BaseTest {
    lateinit var mockNetwork: MockNetwork
    lateinit var vehicle: Vehicle

    fun runFlow(flow: FlowLogic<SignedTransaction>, ap: StartedMockNode): SignedTransaction {
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    fun getIssuedProposal(ownerNode: StartedMockNode, testerNode: StartedMockNode, initiater: StartedMockNode, vehicle: Vehicle = this.vehicle): SignedTransaction {
        val proposal = MOTProposal(testerNode.info.legalIdentities.first(), ownerNode.info.legalIdentities.first(), vehicle, 100.GBP, StatusEnum.DRAFT, initiater.info.legalIdentities.first())
        val flow = MOTProposalIssueFlow(proposal)
        return runFlow(flow, initiater)
    }

    fun getDistributedProposal(ownerNode: StartedMockNode, testerNode: StartedMockNode, initiater: StartedMockNode, vehicle: Vehicle = this.vehicle): SignedTransaction {
        val itx = getIssuedProposal(ownerNode, testerNode, initiater, vehicle)
        val output = itx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalDistributeFlow(output.linearId, output.price.minus(10.GBP))
        return runFlow(flow, initiater)
    }

    fun getAgreedProposal(ownerNode: StartedMockNode, testerNode: StartedMockNode, initiater: StartedMockNode, counter: StartedMockNode, vehicle: Vehicle = this.vehicle): SignedTransaction {
        val dtx = getDistributedProposal(ownerNode, testerNode, initiater, vehicle)
        val output = dtx.tx.outputs.single().data as MOTProposal
        val flow = MOTProposalAgreeFlow(output.linearId)
        return runFlow(flow, counter)
    }

    fun getPaidProposal(ownerNode: StartedMockNode, testerNode: StartedMockNode, vehicle: Vehicle = this.vehicle): SignedTransaction {
        val atx = getAgreedProposal(ownerNode, testerNode, ownerNode, testerNode, vehicle)
        val output = atx.tx.outputs.single().data as MOTProposal
        issueCash(output.price, ownerNode)
        val flow = MOTProposalPayFlow(output.linearId)
        return runFlow(flow, ownerNode)
    }

    fun getIssuedMOT(ownerNode: StartedMockNode, testerNode: StartedMockNode, motTD: Date, motED: Date, vehicle: Vehicle = this.vehicle, result: Boolean = true): SignedTransaction {
        val ptx = getPaidProposal(ownerNode, testerNode, vehicle)
        val proposal = ptx.tx.outputsOfType<MOTProposal>().single()
        val issueMOTFlow = MOTIssueFlow(proposal.linearId, testDate = motTD, expiryDate = motED, loc = "loc", result = result)
        return runFlow(issueMOTFlow, testerNode)
    }

    fun getDraftInsurance(ownerNode: StartedMockNode, insurancerNode: StartedMockNode, initiater: StartedMockNode, effective: Date, expiry: Date, vehicle: Vehicle): SignedTransaction {
        val insurancer = insurancerNode.info.chooseIdentityAndCert().party
        val owner = ownerNode.info.chooseIdentityAndCert().party
        val draft = Insurance(insurancer, owner, vehicle, 100.GBP, "coverage", effective, expiry, initiater.info.legalIdentities.first(), StatusEnum.DRAFT)
        val flow = InsuranceDraftFlow(draft)
        return runFlow(flow, initiater)
    }

    fun getDistributedInsurance(ownerNode: StartedMockNode, insurancerNode: StartedMockNode, initater: StartedMockNode, effective: Date, expiry: Date, vehicle: Vehicle): SignedTransaction {
        val draftTx = getDraftInsurance(ownerNode, insurancerNode, initater, effective, expiry, vehicle)
        val draft = draftTx.tx.outputs.single().data as Insurance
        val distributeFlow = InsuranceDistributeFlow(draft.linearId, draft.price, draft.coverage, effective, expiry)
        return runFlow(distributeFlow, initater)
    }

    fun getAgreedInsurance(ownerNode: StartedMockNode, insurancerNode: StartedMockNode, initater: StartedMockNode, counter: StartedMockNode, effective: Date, expiry: Date, vehicle: Vehicle): SignedTransaction {
        val distributeTx = getDistributedInsurance(ownerNode, insurancerNode, initater, effective, expiry, vehicle)
        val insurance = distributeTx.tx.outputs.single().data as Insurance
        val agreeFlow = InsuranceAgreeFlow(insurance.linearId)
        return runFlow(agreeFlow, counter)
    }

    fun getIssuedInsurance(ownerNode: StartedMockNode, insurancerNode: StartedMockNode, testerNode: StartedMockNode,
                           motTD: Date, motED: Date, effective: Date, expiry: Date, vehicle_mot: Vehicle, vehicle_in: Vehicle, result: Boolean = true): SignedTransaction {
        getIssuedMOT(ownerNode, testerNode, motTD, motED, vehicle_mot)
        val atx = getAgreedInsurance(ownerNode, insurancerNode, ownerNode, insurancerNode, effective, expiry, vehicle_in)
        val insurance = atx.tx.outputs.single().data as Insurance
        val issueFlow = InsuranceIssueFlow(insurance.linearId)
        issueCash(insurance.price, ownerNode)
        return runFlow(issueFlow, insurancerNode)
    }

    fun issueCash(amount: Amount<TokenType>, ap: StartedMockNode) {
        val flow = SelfIssueCashFlow(amount, ap.info.legalIdentities.first())
        val future = ap.startFlow(flow)
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }
}