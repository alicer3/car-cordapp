package com.alice.carapp.contracts

import com.alice.carapp.states.MOT
import com.alice.carapp.states.MOTCopy
import com.alice.carapp.states.MOTProposal
import com.alice.carapp.states.StatusEnum
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException
import java.util.*

class MOTCopyContract: Contract {
    companion object {
        const val ID = "com.alice.carapp.contracts.MOTCopyContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val extraCommands = tx.commandsOfType<InsuranceContract.Commands>()
        if (extraCommands.isNotEmpty() && extraCommands.single().value is InsuranceContract.Commands.Issue) return
        val extraCommands2 = tx.commandsOfType<TAXContract.Commands>()
        if (extraCommands2.isNotEmpty() && extraCommands2.single().value is TAXContract.Commands.Issue) return

        val commands = tx.commandsOfType<Commands>()

        if (commands.isEmpty()) throw IllegalArgumentException("At least one MOTCopyContract Command should be involved.")
        val command = commands.first()

        when (command.value) {
            is Commands.Issue -> {
                val output = tx.outputsOfType<MOTCopy>()
                requireThat {
                    "There should be no input." using (tx.inputs.isEmpty())
                    "There should be one MOTCopy as output." using (output.size == 1)
                }
            }
            is Commands.Cancel -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one MOTCopy as input." using (inputs.single().state.data is MOTCopy)
                    "There should be no output." using (outputs.isEmpty())
                }
            }
        }
    }

    interface Commands: CommandData {
        class Issue: Commands
        class Cancel: Commands
    }
}