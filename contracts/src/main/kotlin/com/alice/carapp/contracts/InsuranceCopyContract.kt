package com.alice.carapp.contracts

import com.alice.carapp.states.InsuranceCopy
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

class InsuranceCopyContract: Contract {
    companion object {
        const val ID = "com.alice.carapp.contracts.InsuranceCopyContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val extraCommands2 = tx.commandsOfType<TAXContract.Commands>()
        if (extraCommands2.isNotEmpty() && extraCommands2.single().value is TAXContract.Commands.Issue) return

        val command = tx.commandsOfType<Commands>().single()

        when (command.value) {
            is Commands.Issue -> {
                val output = tx.outputsOfType<InsuranceCopy>()
                requireThat {
                    "There should be no input." using (tx.inputs.isEmpty())
                    "There should be one MOTCopy as output." using (output.size == 1)
                }
            }
            is Commands.Cancel -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one MOTCopy as input." using (inputs.single().state.data is InsuranceCopy)
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