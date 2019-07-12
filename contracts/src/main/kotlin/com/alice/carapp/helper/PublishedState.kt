package com.alice.carapp.helper

import net.corda.core.contracts.*
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction


@BelongsToContract(PublishedStateContract::class)
data class PublishedState<T: ContractState>(
        val data: T,
        val owner: Party
): ContractState {
    override val participants get() = listOf(owner)
}

class PublishedStateContract: Contract {
    companion object {
        val ID = this::class.java.enclosingClass.canonicalName
    }

    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()

        if (commands.size != 1) throw IllegalArgumentException("One PublishedState Command should be involved.")
        val command = commands.single()

        when (command.value) {
            is Commands.Issue -> {
                val output = tx.outputsOfType<PublishedState<*>>()
                requireThat {
                    "There should be no input." using (tx.inputs.isEmpty())
                    "There should be one PublishedState as output." using (output.size == 1)
                    "All participants should sign." using (command.signers.containsAll((output.single().data.participants.map { it.owningKey })))
                }
            }
            is Commands.Cancel -> {
                val inputs = tx.inputs
                val outputs = tx.outputs
                requireThat {
                    "There should be only one PublishedState as input." using (inputs.single().state.data is PublishedState<*>)
                    "There should be no output." using (outputs.isEmpty())
                }
            }
            is Commands.Consume -> {
                val input = tx.inputsOfType<PublishedState<*>>()
                val output = tx.outputsOfType<PublishedState<*>>()
                requireThat {
                    "There should be no published state as outputs." using output.isEmpty()
                    "There should be at least one published state as input." using input.isNotEmpty()
                    "Owner of all published state should sign." using (input.all { command.signers.contains(it.owner.owningKey) })
                }
            }
        }
    }

    interface Commands: CommandData {
        class Issue: Commands
        class Consume: Commands
        class Cancel: Commands
    }
}