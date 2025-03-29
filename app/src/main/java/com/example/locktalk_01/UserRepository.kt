package com.example.locktalk_01

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class UserRepository {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun registerUser(email: String, password: String, callback: (Boolean) -> Unit) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    userId?.let {
                        saveUserToFirestore(it, email, callback)
                    }
                } else {
                    callback(false)
                }
            }
    }

    private fun saveUserToFirestore(userId: String, email: String, callback: (Boolean) -> Unit) {
        val user = hashMapOf(
            "userId" to userId,
            "email" to email,
            "name" to "New User"
        )

        firestore.collection("Users").document(userId).set(user)
            .addOnSuccessListener { callback(true) }
            .addOnFailureListener { callback(false) }
    }
}