package com.alice.carapp.helper

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class Vehicle(val id: Long,
                   val registrationNo: String,
                   val countryOfRegistration: String,
                   val model: String,
                   val category: String,
                   val mileage: Int) {
    fun isFilled(): Boolean{
        return !this.javaClass.declaredFields.any{ javaClass.getDeclaredField(it.name) == null }
    }
}