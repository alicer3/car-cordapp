package com.alice.carapp.contracts

import com.alice.carapp.helper.PublishedState
import com.alice.carapp.states.*
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrZero
import com.r3.corda.lib.tokens.money.FiatCurrency
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

class TAXContract: Contract {
    companion object {
        const val ID = "com.alice.carapp.contracts.TAXContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()
        if (commands.isEmpty()) throw IllegalArgumentException("At least one TAXContract Command should be involved.")
        val command = commands.first()
        val timeWindow: TimeWindow? = tx.timeWindow

        when (command.value) {
            is Commands.Issue -> {
                //val inputs_insurance = tx.inputsOfType<Insurance>()
                val inputs_cash = tx.inputsOfType<FungibleToken>()
                val outputs_tax = tx.outputsOfType<TAX>()
                val outputs_cash = tx.outputsOfType<FungibleToken>()


                requireThat {
                    "There should be one TAX as outout." using (outputs_tax.size == 1)
                    val tax = outputs_tax.single()
                    "The owner of input cash should be the vehicle owner in TAX." using (inputs_cash.all { it.holder == tax.owner })
                    "The amount of output cash that goes to LTA should be equal to price on TAX." using (outputs_cash.sumTokenStatesOrZero(outputs_cash.first().issuedTokenType).quantity == TAX.price.quantity)
                    "The cash input from owner and output amount should be the same. " using (inputs_cash.sumTokenStatesOrZero(outputs_cash.first().issuedTokenType) == outputs_cash.sumTokenStatesOrZero(outputs_cash.first().issuedTokenType))
                    val now = Date()
                    "The effective date should be either today or future and the expiry date should be in future." using (!tax.effectiveDate.before(now) && tax.expiryDate.after(now))
                    "The effective date should be earlier than expiry date." using (tax.effectiveDate.before(tax.expiryDate))
                    "LTA should sign." using (command.signers.contains(tax.LTA.owningKey))


                    //MOT & Insurance check
                    val published = tx.inputsOfType<PublishedState<*>>()
                    val motlist = published.filter { it.data is MOT }
                    val insurancelist = published.filter { it.data is Insurance }
                    "There should be one MOT published as input." using (motlist.size == 1)

                    val mot = motlist.single().data as MOT
                    "This MOT should have positive result." using (mot.result)
                    "This MOT and TAX should be issued on same vehicle." using (mot.vehicle == tax.vehicle)
                    "This MOT and TAX should be aligned on same owner." using (mot.owner == tax.owner)
                    val oneYearBefore = LocalDateTime.now().minusYears(1)
                    val testDate = LocalDateTime.ofInstant(mot.testDate.toInstant(), ZoneId.systemDefault())
                    "This MOT should be tested within past one year." using (testDate.isAfter(oneYearBefore))
                    "This MOT expiry date should be after the expiry date of TAX." using (mot.expiryDate.after(tax.expiryDate))

                    "There should be one InsuranceCopy as input." using (insurancelist.size == 1)
                    val insurance = insurancelist.single().data as Insurance
                    "This Insurance should be in Issued status." using (insurance.status == StatusEnum.ISSUED)
                    "This Insurance should be issued on same vehicle." using (insurance.vehicle == tax.vehicle)
                    "This Insurance should be issued on same owner." using (insurance.insured == tax.owner)
                    "This Insurance should have an effective date that is before TAX effective date." using (insurance.effectiveDate.before(tax.effectiveDate))
                    "This insurance should have an expiry date that is after TAX expiry date." using (insurance.expiryDate.after(tax.expiryDate))
                }
            }
            is Commands.Update -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one TAX as input." using (inputs.single().state.data is TAX)
                    "There should be only one TAX as output." using (outputs.single().data is TAX)
                    val input = inputs.single().state.data as TAX
                    val output = outputs.single().data as TAX
                    val now = Date()
                    "The effective date should be either today or future and the expiry date should be in future." using (!output.effectiveDate.before(now) && output.expiryDate.after(now))
                    "Only effective date and expiry date could be updated." using (input.copy(effectiveDate = output.effectiveDate, expiryDate = output.expiryDate) == output)
                }
            }
            is Commands.Cancel -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one TAX as input." using (inputs.single().state.data is TAX)
                    "There should be no output." using (outputs.isEmpty())
                }
            }
        }
    }

    interface Commands: CommandData {
        class Issue: Commands
        class Update: Commands
        class Cancel: Commands
    }
}