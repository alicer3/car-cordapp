package com.alice.carapp.contracts

import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException
import java.util.*

class MOTContract: Contract {
    companion object {
        const val ID = "com.alice.carapp.contracts.MOTContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()
        if (commands.isEmpty()) throw IllegalArgumentException("At least one MOTContract Command should be involved.")
        val command = commands.first()
        val timeWindow: TimeWindow? = tx.timeWindow

        when (command.value) {
            is Commands.Issue -> {
                val inputs_proposal = tx.inputsOfType<MOTProposal>()
                val outputs_mot = tx.outputsOfType<MOT>()
                requireThat {
                    "There should be one Paid MOTProposal as input." using (inputs_proposal.single().status == StatusEnum.PAID)
                    val proposal = inputs_proposal.single()
                    "There should be one MOT as output." using (outputs_mot.size == 1)
                    val mot = outputs_mot.single()
                    val now = Date()
                    "The owner, tester and vehicle in MOT should be aligned with MOTProposal." using (proposal.tester == mot.tester && proposal.owner == mot.owner && proposal.vehicle == mot.vehicle)
                    "The test date should be in the past and the expiry date should be in future." using (mot.testDate.before(now) && mot.expiryDate.after(now))
                }
            }
            is Commands.Update -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one MOT as input." using (inputs.single().state.data is MOT)
                    "There should be only one MOT as output." using (outputs.single().data is MOT)
                    val input = inputs.single().state.data as MOT
                    val output = outputs.single().data as MOT
                    val now = Date()
                    "The test date should be in the past and the expiry date should be in future." using (output.testDate.before(now) && output.expiryDate.after(now))
                    "Only test date, expiry date and result could be updated." using (input.copy(testDate = output.testDate, expiryDate = output.expiryDate, result = output.result) == output)
                }
            }
            is Commands.Cancel -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one MOT as input." using (inputs.single().state.data is MOT)
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