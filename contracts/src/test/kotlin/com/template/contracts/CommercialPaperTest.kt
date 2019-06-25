package com.template.contracts

import jdk.nashorn.tools.ShellFunctions.input
import net.corda.core.identity.CordaX500Name
import net.corda.finance.DOLLARS
import net.corda.finance.POUNDS
import net.corda.finance.contracts.CommercialPaper.Companion.CP_PROGRAM_ID
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.apache.commons.lang.time.DateUtils
import org.junit.Test

class CommercialPaperTest {
    private val miniCorp = TestIdentity(CordaX500Name("MiniCorp", "London", "GB"))
    private val megaCorp = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
    private val ledgerServices = MockServices(listOf("net.corda.finance.schemas"), megaCorp, miniCorp)

//    fun getPaper(): State{
//        return State(issuance = megaCorp.ref(),owner = miniCorp.party, faceValue = DOLLARS(1000), maturityDate = null)
//    }
//    @Test
//    fun emptyLedger(){
//        ledgerServices.ledger {
//            val bigCorp = TestIdentity((CordaX500Name("BigCorp", "New York", "GB")))
//        }
//
//    }
//
//    @Test
//    fun simpleCPDoesntCompile() {
//        val inState = getPaper()
//        ledgerServices.ledger(dummyNotary.party)  {
//            transaction {
//                input(CommercialPaper.CP_PROGRAM_ID) { inState }
//            }
//        }
//    }
//
//    // This example test will fail with this exception.
//    @Test(expected = IllegalStateException::class)
//    fun simpleCP() {
//        val inState = getPaper()
//        ledgerServices.ledger(dummyNotary.party) {
//            transaction {
//                attachments(CP_PROGRAM_ID)
//                input(CP_PROGRAM_ID, inState)
//                verifies()
//            }
//        }
//    }
}