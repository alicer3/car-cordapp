package com.alice.carapp.contracts

import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrZero
import com.r3.corda.lib.tokens.money.FiatCurrency
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

class MOTProposalContract: Contract {
    companion object {
        const val ID = "com.alice.carapp.contracts.MOTProposalContract"
    }
    override fun verify(tx: LedgerTransaction) {
        //if (tx.commands.single().value is MOTContract.Commands.Issue) return
        val command = tx.commands.requireSingleCommand<Commands>()
        val timeWindow: TimeWindow? = tx.timeWindow

        when (command.value) {
            is Commands.Draft -> {
                requireThat {
                    "No inputs should be consumed when creating a MOTProposal" using tx.inputs.isEmpty()
                    "There should be one output state of MOTProposal." using (tx.outputs.size == 1 && tx.outputsOfType<MOTProposal>().size == 1)
                    val output = tx.outputsOfType<MOTProposal>().single()
                    "The information about vehicle should be complete." using output.vehicle.isFilled()
                    "The price could be blank, but not negative." using (output.price.quantity > 0)
                    "The status of output should be DRAFT" using (output.status == StatusEnum.DRAFT)
                }
            }
            is Commands.Distribute -> {
                requireThat {
                    "There should be one input and one output." using (tx.inputs.size == 1 && tx.outputs.size == 1)
                    val input = tx.inputsOfType<MOTProposal>().single()
                    val output = tx.outputsOfType<MOTProposal>().single()
                    "The output should be in PENDING status." using (output.status == StatusEnum.PENDING)
                    "The input should be in DRAFT or PENDING status." using (input.status == StatusEnum.PENDING || input.status == StatusEnum.DRAFT)
                    "Only the price could be changed during distribution." using (input.copy(status = output.status, price = output.price, actionParty = output.actionParty) == output)
                    "The price could not be negative" using (output.price.quantity >= 0)
                    "Both garage and owner should sign." using (command.signers.contains(input.owner.owningKey) && command.signers.contains(input.tester.owningKey))
                }
            }
            is Commands.Agree -> {
                requireThat {
                    "There should be only one MOTProposal input." using (tx.inputs.size == 1 && tx.inputsOfType<MOTProposal>().size == 1)
                    "There should be only one MOTProposal output." using ((tx.outputs.size == 1 && tx.outputsOfType<MOTProposal>().size == 1))
                    val input = tx.inputsOfType<MOTProposal>().single()
                    val output = tx.outputsOfType<MOTProposal>().single()
                    "Nothing should be changed during the agreement except for status." using (input.copy(status = output.status) == output)
                    "The input status should be PENDING and the output status should be AGREED." using (input.status == StatusEnum.PENDING && output.status == StatusEnum.AGREED)
                    "Both garage and owner should sign." using (command.signers.contains(input.owner.owningKey) && command.signers.contains(input.tester.owningKey))
                }
            }
            is Commands.Reject -> {
                requireThat {
                    "There should be only one MOTProposal input." using (tx.inputs.size == 1 && tx.inputsOfType<MOTProposal>().size == 1)
                    "There should be only one MOTProposal output." using ((tx.outputs.size == 1 && tx.outputsOfType<MOTProposal>().size == 1))
                    val input = tx.inputsOfType<MOTProposal>().single()
                    val output = tx.outputsOfType<MOTProposal>().single()
                    "Nothing should be changed during the agreement except for status." using (input.copy(status = output.status) == output)
                    "The input status should be PENDING and the output status should be REJECTED." using (input.status == StatusEnum.PENDING && output.status == StatusEnum.REJECTED)
                    //"Both garage and owner should sign." using (command.signers.contains(input.owner.owningKey) && command.signers.contains(input.tester.owningKey))
                }
            }
            is Commands.Update -> {
                requireThat {
                    "There should be only one MOTProposal input." using (tx.inputs.size == 1 && tx.inputsOfType<MOTProposal>().size == 1)
                    "There should be only one MOTProposal output." using ((tx.outputs.size == 1 && tx.outputsOfType<MOTProposal>().size == 1))
                    val input = tx.inputsOfType<MOTProposal>().single()
                    val output = tx.outputsOfType<MOTProposal>().single()
                    "Only price could be updated." using (input.copy(price = output.price) == output)
                    "Both the input status and the output status should be AGREED." using (input.status == StatusEnum.AGREED && output.status == StatusEnum.AGREED)
                    "The price could not be negative" using (output.price.quantity >= 0)
                    "Both garage and owner should sign." using (command.signers.contains(input.owner.owningKey) && command.signers.contains(input.tester.owningKey))
                }
            }
            is Commands.Cancel -> {
                requireThat {
                    "There should be only one MOTProposal input." using (tx.inputs.size == 1 && tx.inputsOfType<MOTProposal>().size == 1)
                    "There should be no MOTProposal output." using (tx.outputs.isEmpty())
                    val input = tx.inputsOfType<MOTProposal>().single()
                    "The input status should be AGREED." using (input.status == StatusEnum.AGREED)
                    "Both garage and owner should sign." using (command.signers.contains(input.owner.owningKey) && command.signers.contains(input.tester.owningKey))
                }
            }
            is Commands.Pay -> {
                val inputs_proposal = tx.inputsOfType<MOTProposal>()
                val inputs_cash = tx.inputsOfType<FungibleToken<FiatCurrency>>()
                val outputs_proposal = tx.outputsOfType<MOTProposal>()
                val outputs_cash = tx.outputsOfType<FungibleToken<FiatCurrency>>()
                requireThat {
                    "There should be one Agreed MOTProposal as input." using (inputs_proposal.single().status == StatusEnum.AGREED)
                    val proposal = inputs_proposal.single()
                    "There should be cash as input." using (inputs_cash.isNotEmpty())
                    "There should be one Paid MOTProposal as output." using (outputs_proposal.single().status == StatusEnum.PAID)
                    val proposalPaid = outputs_proposal.single()
                    "The owner of input cash should be the vehicle owner in MOTProposal." using (inputs_cash.all { it.holder == proposal.owner })
                    "The amount of output cash that goes to tester should be equal to price on proposal." using (outputs_cash.filter { it.holder == proposal.tester }.sumTokenStatesOrZero(outputs_cash.first().issuedTokenType).quantity == proposal.price.quantity)
                    "The cash input from owner and output amount should be the same. " using (inputs_cash.sumTokenStatesOrZero(outputs_cash.first().issuedTokenType).quantity == outputs_cash.sumTokenStatesOrZero(outputs_cash.first().issuedTokenType).quantity)
                    "MOTProposal should be consistent except for the status." using (proposal.copy(status = StatusEnum.PAID) == proposalPaid)
                    "Both garage and owner should sign." using (command.signers.contains(proposal.owner.owningKey) && command.signers.contains(proposal.tester.owningKey))

                }
            }
            is Commands.Consume -> {
                val inputs = tx.inputsOfType<MOTProposal>()
                val outputs = tx.outputsOfType<MOTProposal>()
                requireThat {
                    "There should be one Paid MOTProposal as input." using (inputs.single().status == StatusEnum.PAID)
                    "There should not be any MOTProposal as output." using (outputs.isEmpty())
                    "There should be MOT issued." using (tx.outputsOfType<MOT>().size == 1)
                }
            }
        }
    }

    interface Commands: CommandData {
        class Draft: Commands
        class Distribute: Commands
        class Agree: Commands
        class Reject: Commands
        class Update: Commands
        class Cancel: Commands
        class Pay: Commands
        class Consume: Commands
    }
}