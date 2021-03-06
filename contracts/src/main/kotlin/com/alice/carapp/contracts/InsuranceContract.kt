package com.alice.carapp.contracts

import com.alice.carapp.helper.PublishedState
import com.alice.carapp.states.Insurance
import com.alice.carapp.states.MOT
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrZero
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.time.LocalDateTime
import java.time.ZoneId

class InsuranceContract : Contract {
    companion object {
        const val ID = "com.alice.carapp.contracts.InsuranceContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()
        if (commands.isEmpty()) throw IllegalArgumentException("At least one Insurance Command should be involved.")
        val command = commands.first()

        // common constraints on Insurance
        if (tx.outputsOfType<Insurance>().size == 1) { // when the command is not cancel
            val output = tx.outputsOfType<Insurance>().single()
            requireThat {
                "The price could not be negative." using (output.price.quantity > 0)
                "The expiry date should be later than effective date." using (output.expiryDate.after(output.effectiveDate))
            }
        }

        // command specific constraints
        when (command.value) {
            is Commands.Draft -> {
                requireThat {
                    "No inputs should be consumed when drafting an Insurance." using tx.inputs.isEmpty()
                    "There should only be one output state of Insurance." using (tx.outputs.size == 1 && tx.outputsOfType<Insurance>().size == 1)
                    val output = tx.outputsOfType<Insurance>().single()
                    "The information about vehicle should be complete." using output.vehicle.isFilled()
                    "The status of output should be DRAFT" using (output.status == StatusEnum.DRAFT)
                }
            }
            is Commands.Distribute -> {
                "There should be one Insurance input." using (tx.inputs.size == 1 && tx.inputs.single().state.data is Insurance)
                "There should be one Insurance output." using (tx.outputs.size == 1 && tx.outputs.single().data is Insurance)
                val input = tx.inputsOfType<Insurance>().single()
                val output = tx.outputsOfType<Insurance>().single()
                requireThat {
                    "Output should be PENDING status." using (output.status == StatusEnum.PENDING)
                    "Input should be DRAFT or PENDING status." using (input.status == StatusEnum.PENDING || input.status == StatusEnum.DRAFT)
                    "Only the price, dates and coverage could be changed during distribution." using (input.copy(status = output.status,
                            price = output.price, expiryDate = output.expiryDate, effectiveDate = output.effectiveDate, coverage = output.coverage,
                            actionParty = output.actionParty) == output)
                    "Both insurancer and insured should sign." using (command.signers.contains(input.insurancer.owningKey) && command.signers.contains(input.insured.owningKey))
                }
            }
            is Commands.Agree -> {
                requireThat {
                    "There should be one Insurance input." using (tx.inputs.size == 1 && tx.inputs.single().state.data is Insurance)
                    "There should be one Insurance output." using (tx.outputs.size == 1 && tx.outputs.single().data is Insurance)
                    val input = tx.inputsOfType<Insurance>().single()
                    val output = tx.outputsOfType<Insurance>().single()
                    "Nothing should be changed during the agreement except for status." using (input.copy(status = output.status) == output)
                    "The input status should be PENDING and the output status should be AGREED." using (input.status == StatusEnum.PENDING && output.status == StatusEnum.AGREED)
                    "Both insurancer and insured should sign." using (command.signers.contains(input.insured.owningKey) && command.signers.contains(input.insurancer.owningKey))
                }
            }
            is Commands.Reject -> {
                requireThat {
                    "There should be one Insurance input." using (tx.inputs.size == 1 && tx.inputs.single().state.data is Insurance)
                    "There should be one Insurance output." using (tx.outputs.size == 1 && tx.outputs.single().data is Insurance)
                    val input = tx.inputsOfType<Insurance>().single()
                    val output = tx.outputsOfType<Insurance>().single()
                    "Nothing should be changed during the agreement except for status." using (input.copy(status = output.status) == output)
                    "The input status should be PENDING and the output status should be REJECTED." using (input.status == StatusEnum.PENDING && output.status == StatusEnum.REJECTED)
                    //"Both insurancer and insured should sign." using (command.signers.contains(input.insured.owningKey) && command.signers.contains(input.tester.owningKey))
                }
            }
            is Commands.Issue -> {
                val inputsInsurance = tx.inputsOfType<Insurance>()
                val inputsCash = tx.inputsOfType<FungibleToken>()
                val outputsInsurance = tx.outputsOfType<Insurance>()
                val outputsCash = tx.outputsOfType<FungibleToken>()



                requireThat {
                    "There should be one Agreed Insurance as input." using (inputsInsurance.single().status == StatusEnum.AGREED)
                    val insurance = inputsInsurance.single()
                    "There should be cash as input." using (inputsCash.isNotEmpty())
                    "There should be one ISSUED Insurance as output." using (outputsInsurance.single().status == StatusEnum.ISSUED)
                    val insuranceIssued = outputsInsurance.single()
                    "The owner of input cash should be the vehicle owner in Insurance." using (inputsCash.all { it.holder == insurance.insured })
                    "The amount of output cash that goes to insurancer should be equal to price on insurance." using (outputsCash.filter { it.holder == insurance.insurancer }.sumTokenStatesOrZero(outputsCash.first().issuedTokenType).quantity == insurance.price.quantity)
                    "The cash input from owner and output amount should be the same. " using (inputsCash.sumTokenStatesOrZero(outputsCash.first().issuedTokenType).quantity == outputsCash.sumTokenStatesOrZero(outputsCash.first().issuedTokenType).quantity)
                    "Insurance should be consistent except for the status." using (insurance.copy(status = StatusEnum.ISSUED) == insuranceIssued)
                    "Both insurancer and insured should sign." using (command.signers.contains(insurance.insurancer.owningKey) && command.signers.contains(insurance.insured.owningKey))

                    // requirement on Published MOT
                    "There should be one published MOT as input." using (tx.inputsOfType<PublishedState<MOT>>().size == 1)
                    val mot = tx.inputsOfType<PublishedState<MOT>>().single().data
                    "This MOT should have positive result." using (mot.result)
                    "This MOT and insurance should be issued on same vehicle." using (mot.vehicle == insurance.vehicle)
                    "This MOT and insurance should be aligned on same owner." using (mot.owner == insurance.insured)
                    val oneYearBefore = LocalDateTime.now().minusYears(1)
                    val testDate = LocalDateTime.ofInstant(mot.testDate.toInstant(), ZoneId.systemDefault())
                    "This MOT should be tested within past one year." using (testDate.isAfter(oneYearBefore))
                    "This MOT expiry date should be after the expiry date of Insurance." using (mot.expiryDate.after(insurance.expiryDate))
                }
            }
            is Commands.Update -> {
                requireThat {
                    "There should be one Insurance input." using (tx.inputs.size == 1 && tx.inputs.single().state.data is Insurance)
                    "There should be one Insurance output." using (tx.outputs.size == 1 && tx.outputs.single().data is Insurance)
                    val input = tx.inputsOfType<Insurance>().single()
                    val output = tx.outputsOfType<Insurance>().single()
                    "Only the price, dates and coverage could be changed during distribution." using (input.copy(status = output.status,
                            price = output.price, expiryDate = output.expiryDate, effectiveDate = output.effectiveDate, coverage = output.coverage,
                            actionParty = output.actionParty) == output)
                    "Both the input status and the output status should be AGREED." using (input.status == StatusEnum.AGREED && output.status == StatusEnum.AGREED)
                    "Both insurancer and insured should sign." using (command.signers.contains(input.insurancer.owningKey) && command.signers.contains(input.insured.owningKey))
                }
            }
            is Commands.Cancel -> {
                requireThat {
                    "There should be one Insurance input." using (tx.inputs.size == 1 && tx.inputs.single().state.data is Insurance)
                    "There should be no Insurance output." using (tx.outputs.isEmpty())
                    val input = tx.inputsOfType<Insurance>().single()
                    "The input status should be AGREED." using (input.status == StatusEnum.AGREED)
                    "Both insurancer and insured should sign." using (command.signers.contains(input.insured.owningKey) && command.signers.contains(input.insurancer.owningKey))
                }
            }

        }
    }

    interface Commands : CommandData {
        class Draft : Commands
        class Distribute : Commands
        class Agree : Commands
        class Reject : Commands // to be implemented
        class Issue : Commands
        class Update : Commands
        class Cancel : Commands
    }
}